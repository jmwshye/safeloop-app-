import { Injectable } from '@nestjs/common';
import { createHash } from 'node:crypto';
import {
  and,
  asc,
  desc,
  eq,
  exists,
  gt,
  inArray,
  isNull,
  lte,
  ne,
  notExists,
  or,
  sql,
} from 'drizzle-orm';
import { DatabaseService } from '../database.service.js';
import {
  attendances,
  siteMemberships,
  sites,
  users,
} from '../database/schema.js';
import {
  emergencyDialIntents,
  incidentPushes,
  incidentRecipients,
  incidentRequests,
  incidents,
  notificationPreferences,
  pushDevices,
} from '../database/incident-schema.js';
import { PushSender } from '../notifications/push-sender.js';
import { appendEvent, bindPhoto, siteAccess } from '../records/records.js';
import { IncidentError } from './incident.errors.js';
import type {
  CreateIncidentInput,
  ResolutionInput,
  WorkerReplyInput,
} from './incident.schemas.js';

type Tx = Parameters<Parameters<DatabaseService['db']['transaction']>[0]>[0];
type Call = typeof incidents.$inferSelect;
type Recipient = typeof incidentRecipients.$inferSelect;
type Push = typeof incidentPushes.$inferSelect;
const selfWait = 15_000;
const responderWait = 30_000;
const verificationWait = 120_000;
const seoulWorkDate = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

@Injectable()
export class IncidentService {
  private stopping = false;
  constructor(
    private readonly database: DatabaseService,
    private readonly sender: PushSender,
  ) {}
  private now() {
    return new Date();
  }

  private async log(
    tx: Tx,
    c: Call,
    type: string,
    actorId: string | null,
    details: Record<string, unknown> = {},
  ) {
    await appendEvent(tx, {
      siteId: c.siteId,
      entityId: c.id,
      subjectId: c.workerId,
      actorId,
      type: 'incident.' + type,
      source: actorId ? 'human' : 'system',
      details,
    });
  }
  private async change(
    tx: Tx,
    c: Call,
    patch: Partial<typeof incidents.$inferInsert>,
  ) {
    const [updated] = await tx
      .update(incidents)
      .set(patch)
      .where(eq(incidents.id, c.id))
      .returning();
    return updated;
  }
  private async locked(tx: Tx, id: string) {
    const [c] = await tx
      .select()
      .from(incidents)
      .where(eq(incidents.id, id))
      .for('no key update');
    if (!c) throw new IncidentError('INCIDENT_NOT_FOUND');
    return c;
  }
  private async recipient(tx: Tx, c: Call, userId: string) {
    const [r] = await tx
      .select()
      .from(incidentRecipients)
      .where(
        and(
          eq(incidentRecipients.incidentId, c.id),
          eq(incidentRecipients.userId, userId),
        ),
      );
    return r;
  }
  private async foreman(tx: Tx, c: Call, userId: string) {
    const [manager] = await tx
      .select({ id: sites.id })
      .from(sites)
      .where(and(eq(sites.id, c.siteId), eq(sites.foremanUserId, userId)));
    return Boolean(manager);
  }
  private async onShift(
    tx: Tx,
    c: Call,
    userId: string,
    now: Date,
    excludeHighRisk = false,
  ) {
    const [shift] = await tx
      .select({ id: attendances.id })
      .from(attendances)
      .innerJoin(
        siteMemberships,
        and(
          eq(siteMemberships.siteId, c.siteId),
          eq(siteMemberships.userId, userId),
        ),
      )
      .where(
        and(
          eq(attendances.siteId, c.siteId),
          eq(attendances.userId, userId),
          eq(attendances.workDate, seoulWorkDate.format(now)),
          isNull(attendances.checkedOutAt),
          excludeHighRisk ? isNull(attendances.highRiskTask) : undefined,
        ),
      )
      .limit(1);
    return Boolean(shift);
  }
  private async canView(tx: Tx, c: Call, userId: string) {
    if (c.workerId === userId || (await this.foreman(tx, c, userId))) return;
    const [member] = await tx
      .select({ id: siteMemberships.userId })
      .from(incidentRecipients)
      .innerJoin(
        siteMemberships,
        and(
          eq(siteMemberships.siteId, c.siteId),
          eq(siteMemberships.userId, incidentRecipients.userId),
        ),
      )
      .where(
        and(
          eq(incidentRecipients.incidentId, c.id),
          eq(incidentRecipients.userId, userId),
        ),
      );
    if (!member) throw new IncidentError('INCIDENT_NOT_FOUND');
  }
  private async contactEligible(tx: Tx, c: Call, userId: string, now: Date) {
    return userId === c.foremanId
      ? this.foreman(tx, c, userId)
      : this.onShift(tx, c, userId, now, userId !== c.buddyId);
  }
  private async view(tx: Tx, c: Call, viewer: string, report = false) {
    const recipients = await tx
      .select({
        userId: incidentRecipients.userId,
        name: users.name,
        role: incidentRecipients.role,
        requestedAt: incidentRecipients.requestedAt,
        sentAt: incidentRecipients.sentAt,
        receivedAt: incidentRecipients.receivedAt,
        openedAt: incidentRecipients.openedAt,
        acceptedAt: incidentRecipients.acceptedAt,
        declinedAt: incidentRecipients.declinedAt,
        delivery: incidentPushes.status,
      })
      .from(incidentRecipients)
      .innerJoin(users, eq(users.id, incidentRecipients.userId))
      .leftJoin(
        incidentPushes,
        and(
          eq(incidentPushes.incidentId, c.id),
          eq(incidentPushes.userId, incidentRecipients.userId),
          eq(incidentPushes.type, 'alert'),
        ),
      )
      .where(eq(incidentRecipients.incidentId, c.id))
      .orderBy(asc(incidentRecipients.requestedAt), asc(users.id));
    const [worker] = await tx
      .select({ id: users.id, name: users.name })
      .from(users)
      .where(eq(users.id, c.workerId));
    const dialIntents = await tx
      .select({
        userId: emergencyDialIntents.userId,
        openedAt: emergencyDialIntents.openedAt,
      })
      .from(emergencyDialIntents)
      .where(eq(emergencyDialIntents.incidentId, c.id))
      .orderBy(asc(emergencyDialIntents.openedAt));
    // Past movements never enter the manager's history or exported incident data.
    const { location, sensorWindow, ...evidence } = c;
    const notices = await tx
      .select({
        userId: incidentPushes.userId,
        type: incidentPushes.type,
        status: incidentPushes.status,
        requestedAt: incidentPushes.requestedAt,
        sentAt: incidentPushes.sentAt,
      })
      .from(incidentPushes)
      .where(
        and(
          eq(incidentPushes.incidentId, c.id),
          ne(incidentPushes.type, 'alert'),
        ),
      )
      .orderBy(asc(incidentPushes.requestedAt));
    const contact = recipients.find((r) => r.userId === viewer);
    const deciding =
      !report &&
      !c.closedAt &&
      contact &&
      contact.role !== 'foreman' &&
      !contact.declinedAt &&
      !c.responderId &&
      (await this.contactEligible(tx, c, viewer, this.now()));
    const managing =
      contact?.role === 'foreman' && (await this.foreman(tx, c, viewer));
    const showPosition =
      !report &&
      !c.closedAt &&
      (viewer === c.workerId ||
        viewer === c.responderId ||
        deciding ||
        managing);
    return {
      ...evidence,
      worker,
      recipients,
      dialIntents,
      notices,
      location: showPosition ? location : null,
      sensorWindow: !report && viewer === c.workerId ? sensorWindow : null,
    };
  }
  private async notify(
    tx: Tx,
    c: Call,
    userId: string,
    type: Push['type'],
    now: Date,
  ) {
    return tx
      .insert(incidentPushes)
      .values({
        incidentId: c.id,
        userId,
        type,
        requestedAt: now,
        nextAttemptAt: now,
      })
      .onConflictDoNothing()
      .returning({ id: incidentPushes.id });
  }
  private async invite(
    tx: Tx,
    c: Call,
    userId: string,
    role: Recipient['role'],
    now: Date,
  ) {
    if (userId === c.workerId) return;
    const inserted = await tx
      .insert(incidentRecipients)
      .values({ incidentId: c.id, userId, role, requestedAt: now })
      .onConflictDoNothing()
      .returning({ id: incidentRecipients.id });
    if (inserted.length) await this.notify(tx, c, userId, 'alert', now);
  }
  private async startCalls(tx: Tx, c: Call, now: Date) {
    const next = await this.change(tx, c, {
      status: 'calling',
      escalatedAt: now,
      nextCallAt: new Date(now.getTime() + responderWait),
    });
    if (
      c.buddyId &&
      c.buddyId !== c.foremanId &&
      (await this.onShift(tx, c, c.buddyId, now))
    )
      await this.invite(tx, next, c.buddyId, 'buddy', now);
    await this.invite(tx, next, c.foremanId, 'foreman', now);
    await this.log(tx, next, 'contacts_requested', null);
    return next;
  }
  private async nextCandidate(tx: Tx, c: Call, now: Date) {
    const alreadyInvited = tx
      .select({ id: incidentRecipients.id })
      .from(incidentRecipients)
      .where(
        and(
          eq(incidentRecipients.incidentId, c.id),
          eq(incidentRecipients.userId, attendances.userId),
        ),
      );
    const busy = tx
      .select({ id: incidents.id })
      .from(incidents)
      .where(
        and(
          isNull(incidents.closedAt),
          or(
            eq(incidents.responderId, attendances.userId),
            eq(incidents.workerId, attendances.userId),
          ),
        ),
      );
    const [candidate] = await tx
      .select({ userId: attendances.userId })
      .from(attendances)
      .innerJoin(
        siteMemberships,
        and(
          eq(siteMemberships.siteId, c.siteId),
          eq(siteMemberships.userId, attendances.userId),
        ),
      )
      .where(
        and(
          eq(attendances.siteId, c.siteId),
          eq(attendances.zone, c.zone),
          isNull(attendances.highRiskTask),
          eq(attendances.workDate, seoulWorkDate.format(now)),
          isNull(attendances.checkedOutAt),
          ne(attendances.userId, c.workerId),
          notExists(alreadyInvited),
          notExists(busy),
        ),
      )
      .orderBy(asc(attendances.checkedInAt), asc(attendances.id))
      .limit(1);
    if (!candidate) {
      if (c.status !== 'unanswered')
        await this.log(tx, c, 'no_responder', null);
      return this.change(tx, c, { status: 'unanswered', nextCallAt: null });
    }
    await this.invite(tx, c, candidate.userId, 'zone', now);
    await this.log(tx, c, 'zone_requested', null, {
      recipientId: candidate.userId,
    });
    return this.change(tx, c, {
      status: 'calling',
      nextCallAt: new Date(now.getTime() + responderWait),
    });
  }
  private async advance(tx: Tx, c: Call, now: Date) {
    if (
      c.status === 'awaiting_worker' &&
      c.workerDueAt &&
      c.workerDueAt <= now
    ) {
      await this.log(tx, c, 'worker_unanswered', null);
      return this.startCalls(tx, c, now);
    }
    if (c.status === 'calling' && c.nextCallAt && c.nextCallAt <= now) {
      await this.log(tx, c, 'response_delayed', null);
      return this.nextCandidate(tx, c, now);
    }
    if (
      (c.status === 'responding' || c.status === 'arrived') &&
      !c.observedAt &&
      c.acceptedAt &&
      now.getTime() - c.acceptedAt.getTime() >= verificationWait
    ) {
      const inserted = await this.notify(
        tx,
        c,
        c.foremanId,
        'verification_overdue',
        now,
      );
      if (inserted.length) await this.log(tx, c, 'verification_overdue', null);
    }
    return c;
  }
  async open(tx: Tx, userId: string, input: CreateIncidentInput) {
    const now = this.now();
    await tx
      .select({ id: users.id })
      .from(users)
      .where(eq(users.id, userId))
      .for('no key update');
    const fingerprint = createHash('sha256')
      .update(
        JSON.stringify([
          input.siteId,
          input.source,
          input.peakG ?? null,
          input.sensorWindow ?? null,
          input.location ?? null,
        ]),
      )
      .digest('hex');
    const [prior] = await tx
      .select()
      .from(incidentRequests)
      .where(
        and(
          eq(incidentRequests.workerId, userId),
          eq(incidentRequests.requestId, input.requestId),
        ),
      );
    if (prior) {
      if (prior.fingerprint !== fingerprint)
        throw new IncidentError('REQUEST_ID_CONFLICT');
      return {
        created: false,
        incident: await this.view(
          tx,
          await this.locked(tx, prior.incidentId),
          userId,
        ),
      };
    }
    const [site] = await tx
      .select({
        id: sites.id,
        foremanId: sites.foremanUserId,
        member: siteMemberships.userId,
        zone: siteMemberships.zone,
      })
      .from(sites)
      .leftJoin(
        siteMemberships,
        and(
          eq(siteMemberships.siteId, sites.id),
          eq(siteMemberships.userId, userId),
        ),
      )
      .where(eq(sites.id, input.siteId));
    if (!site || (!site.member && site.foremanId !== userId))
      throw new IncidentError('SITE_NOT_FOUND');
    let [c] = await tx
      .select()
      .from(incidents)
      .where(and(eq(incidents.workerId, userId), isNull(incidents.closedAt)))
      .for('no key update');
    if (c && c.siteId !== site.id)
      throw new IncidentError('INCIDENT_STATE_CONFLICT');
    const created = !c;
    if (!c) {
      const [shift] = await tx
        .select()
        .from(attendances)
        .where(
          and(
            eq(attendances.siteId, site.id),
            eq(attendances.userId, userId),
            eq(attendances.workDate, seoulWorkDate.format(now)),
            isNull(attendances.checkedOutAt),
          ),
        )
        .orderBy(desc(attendances.checkedInAt))
        .limit(1);
      if (!shift && input.source === 'abnormal_movement')
        throw new IncidentError('SHIFT_REQUIRED');
      const manual = input.source === 'manual_help';
      [c] = await tx
        .insert(incidents)
        .values({
          workerId: userId,
          siteId: site.id,
          foremanId: site.foremanId,
          buddyId: shift?.buddyUserId ?? null,
          zone: shift?.zone ?? site.zone ?? '',
          attendanceId: shift?.id ?? null,
          source: input.source,
          status: 'awaiting_worker',
          createdAt: now,
          workerDueAt: manual ? null : new Date(now.getTime() + selfWait),
          workerResponse: manual ? 'help_needed' : null,
          workerRespondedAt: manual ? now : null,
          location: input.location,
          peakG: input.peakG,
          sensorWindow: input.sensorWindow,
        })
        .returning();
      await this.log(
        tx,
        c,
        manual ? 'help_requested' : 'signal_detected',
        manual ? userId : null,
        input.peakG === undefined ? {} : { peakG: input.peakG },
      );
      if (manual) c = await this.startCalls(tx, c, now);
    } else if (
      input.source === 'manual_help' &&
      c.status === 'awaiting_worker'
    ) {
      c = await this.change(tx, c, {
        workerResponse: 'help_needed',
        workerRespondedAt: now,
      });
      await this.log(tx, c, 'help_requested', userId);
      c = await this.startCalls(tx, c, now);
    }
    const stored = await tx
      .insert(incidentRequests)
      .values({
        workerId: userId,
        requestId: input.requestId,
        incidentId: c.id,
        fingerprint,
      })
      .onConflictDoNothing()
      .returning({ id: incidentRequests.incidentId });
    if (!stored.length) throw new IncidentError('REQUEST_ID_CONFLICT');
    return { created, incident: await this.view(tx, c, userId) };
  }
  async create(userId: string, input: CreateIncidentInput) {
    return this.database.db.transaction((tx) => this.open(tx, userId, input));
  }
  async detail(userId: string, id: string) {
    return this.database.db.transaction(async (tx) => {
      let c = await this.locked(tx, id);
      await this.canView(tx, c, userId);
      c = await this.advance(tx, c, this.now());
      return { incident: await this.view(tx, c, userId) };
    });
  }
  async reportDetail(
    siteId: string,
    id: string,
    viewerId: string,
    snapshot?: Tx,
  ) {
    const read = async (tx: Tx) => {
      const [c] = await tx.select().from(incidents).where(eq(incidents.id, id));
      if (
        !c ||
        c.siteId !== siteId ||
        (viewerId !== c.workerId && !(await this.foreman(tx, c, viewerId)))
      )
        throw new IncidentError('INCIDENT_NOT_FOUND');
      return { incident: await this.view(tx, c, viewerId, true) };
    };
    return snapshot
      ? read(snapshot)
      : this.database.db.transaction(read, {
          isolationLevel: 'repeatable read',
          accessMode: 'read only',
        });
  }
  async active(userId: string, siteId?: string) {
    const db = this.database.db;
    const managed = db
      .select({ id: sites.id })
      .from(sites)
      .where(
        and(eq(sites.id, incidents.siteId), eq(sites.foremanUserId, userId)),
      );
    const contacted = db
      .select({ id: incidentRecipients.id })
      .from(incidentRecipients)
      .innerJoin(
        siteMemberships,
        and(
          eq(siteMemberships.siteId, incidents.siteId),
          eq(siteMemberships.userId, userId),
        ),
      )
      .where(
        and(
          eq(incidentRecipients.incidentId, incidents.id),
          eq(incidentRecipients.userId, userId),
        ),
      );
    const rows = await db
      .select({ id: incidents.id })
      .from(incidents)
      .where(
        and(
          siteId ? eq(incidents.siteId, siteId) : undefined,
          isNull(incidents.closedAt),
          or(
            eq(incidents.workerId, userId),
            exists(managed),
            exists(contacted),
          ),
        ),
      )
      .orderBy(desc(incidents.createdAt))
      .limit(100);
    const visible = [];
    for (const row of rows) {
      const { incident } = await this.detail(userId, row.id);
      if (!incident.closedAt) visible.push(incident);
    }
    return { incidents: visible };
  }
  // All actions lock the same incident first. A simultaneous accept or expiry sees one transition.
  private async action(
    userId: string,
    id: string,
    act: (tx: Tx, c: Call, now: Date) => Promise<Call>,
  ) {
    return this.database.db.transaction(async (tx) => {
      const original = await this.locked(tx, id);
      await this.canView(tx, original, userId);
      const now = this.now();
      const current = await this.advance(tx, original, now);
      return {
        incident: await this.view(tx, await act(tx, current, now), userId),
      };
    });
  }
  private async cancelQueuedAlerts(tx: Tx, c: Call) {
    await tx
      .update(incidentPushes)
      .set({ status: 'cancelled' })
      .where(
        and(
          eq(incidentPushes.incidentId, c.id),
          eq(incidentPushes.type, 'alert'),
          eq(incidentPushes.status, 'queued'),
        ),
      );
  }
  private async updateContacts(
    tx: Tx,
    c: Call,
    userId: string,
    type: 'worker_safe' | 'closed',
    now: Date,
  ) {
    const contacts = await tx
      .select({ userId: incidentRecipients.userId })
      .from(incidentRecipients)
      .where(eq(incidentRecipients.incidentId, c.id));
    const people = new Set([
      c.workerId,
      ...contacts.map((person) => person.userId),
    ]);
    for (const person of people)
      if (person !== userId) await this.notify(tx, c, person, type, now);
  }
  workerReply(
    userId: string,
    id: string,
    { requestId, response }: WorkerReplyInput,
  ) {
    return this.action(userId, id, async (tx, c, now) => {
      if (c.workerId !== userId)
        throw new IncidentError('INCIDENT_ACTION_FORBIDDEN');
      // A late retry of an older button press must never overwrite a newer response.
      const fingerprint = 'worker-response:' + id + ':' + response;
      const claimed = await tx
        .insert(incidentRequests)
        .values({
          workerId: userId,
          requestId,
          incidentId: id,
          fingerprint,
        })
        .onConflictDoNothing()
        .returning({ id: incidentRequests.incidentId });
      if (!claimed.length) {
        const [prior] = await tx
          .select({ fingerprint: incidentRequests.fingerprint })
          .from(incidentRequests)
          .where(
            and(
              eq(incidentRequests.workerId, userId),
              eq(incidentRequests.requestId, requestId),
            ),
          );
        if (prior?.fingerprint !== fingerprint)
          throw new IncidentError('REQUEST_ID_CONFLICT');
        return c;
      }
      if (c.workerResponse === response) return c;
      if (c.closedAt) throw new IncidentError('INCIDENT_STATE_CONFLICT');
      let updated = await this.change(tx, c, {
        workerResponse: response,
        workerRespondedAt: now,
      });
      await this.log(
        tx,
        updated,
        response === 'okay' ? 'worker_safe' : 'help_requested',
        userId,
      );
      if (response === 'help_needed')
        return c.status === 'awaiting_worker'
          ? this.startCalls(tx, updated, now)
          : updated;
      if (c.status !== 'awaiting_worker') {
        await this.updateContacts(tx, c, userId, 'worker_safe', now);
        return updated;
      }
      updated = await this.change(tx, updated, {
        status: 'closed',
        resolution: 'worker_confirmed_okay',
        closedAt: now,
        nextCallAt: null,
      });
      await this.cancelQueuedAlerts(tx, c);
      await this.updateContacts(tx, c, userId, 'worker_safe', now);
      return updated;
    });
  }
  receipt(userId: string, id: string, stage: 'received' | 'opened') {
    return this.action(userId, id, async (tx, c, now) => {
      const r = await this.recipient(tx, c, userId);
      if (!r) throw new IncidentError('INCIDENT_ACTION_FORBIDDEN');
      const key = stage === 'received' ? 'receivedAt' : 'openedAt';
      if (!r[key]) {
        await tx
          .update(incidentRecipients)
          .set({ [key]: now })
          .where(eq(incidentRecipients.id, r.id));
        await this.log(tx, c, stage, stage === 'received' ? null : userId, {
          recipientId: userId,
        });
      }
      return c;
    });
  }
  answerCall(userId: string, id: string, accept: boolean) {
    return this.action(userId, id, async (tx, c, now) => {
      const r = await this.recipient(tx, c, userId);
      if (!r || userId === c.workerId)
        throw new IncidentError('INCIDENT_ACTION_FORBIDDEN');
      if ((accept && c.responderId === userId) || (!accept && r.declinedAt))
        return c;
      if (!['calling', 'unanswered'].includes(c.status) || r.declinedAt)
        throw new IncidentError('INCIDENT_STATE_CONFLICT');
      if (!accept) {
        await tx
          .update(incidentRecipients)
          .set({ declinedAt: now })
          .where(eq(incidentRecipients.id, r.id));
        await tx
          .update(incidentPushes)
          .set({ status: 'cancelled' })
          .where(
            and(
              eq(incidentPushes.incidentId, id),
              eq(incidentPushes.userId, userId),
              eq(incidentPushes.type, 'alert'),
              eq(incidentPushes.status, 'queued'),
            ),
          );
        await this.log(tx, c, 'declined', userId);
        // An earlier recipient may decline after the next worker has already been invited.
        const [waiting] = await tx
          .select({ id: incidentRecipients.id })
          .from(incidentRecipients)
          .where(
            and(
              eq(incidentRecipients.incidentId, id),
              ne(incidentRecipients.role, 'foreman'),
              isNull(incidentRecipients.declinedAt),
              r.role === 'foreman'
                ? undefined
                : gt(incidentRecipients.requestedAt, r.requestedAt),
            ),
          )
          .limit(1);
        return waiting ? c : this.nextCandidate(tx, c, now);
      }
      await tx.execute(
        sql`select pg_advisory_xact_lock(hashtextextended('incident-accept:' || ${userId}::text,0))`,
      );
      const [busy] = await tx
        .select({ id: incidents.id })
        .from(incidents)
        .where(
          and(
            isNull(incidents.closedAt),
            or(
              eq(incidents.responderId, userId),
              eq(incidents.workerId, userId),
            ),
          ),
        )
        .limit(1);
      if (busy) throw new IncidentError('INCIDENT_STATE_CONFLICT');
      if (!(await this.contactEligible(tx, c, userId, now)))
        throw new IncidentError('INCIDENT_STATE_CONFLICT');
      await tx
        .update(incidentRecipients)
        .set({ acceptedAt: now })
        .where(eq(incidentRecipients.id, r.id));
      const updated = await this.change(tx, c, {
        status: 'responding',
        responderId: userId,
        acceptedAt: now,
        nextCallAt: null,
      });
      // The manager's original notification still needs to go out.
      await tx
        .update(incidentPushes)
        .set({ status: 'cancelled' })
        .where(
          and(
            eq(incidentPushes.incidentId, id),
            ne(incidentPushes.userId, c.foremanId),
            eq(incidentPushes.type, 'alert'),
            eq(incidentPushes.status, 'queued'),
          ),
        );
      await this.log(tx, c, 'accepted', userId, {
        role: r.role,
        responseSeconds: Math.max(
          0,
          Math.floor((now.getTime() - r.requestedAt.getTime()) / 1000),
        ),
      });
      return updated;
    });
  }
  arrive(userId: string, id: string) {
    return this.action(userId, id, async (tx, c, now) => {
      if (c.responderId !== userId)
        throw new IncidentError('INCIDENT_ACTION_FORBIDDEN');
      if (c.arrivedAt) return c;
      if (c.status !== 'responding')
        throw new IncidentError('INCIDENT_STATE_CONFLICT');
      await this.log(tx, c, 'arrived', userId);
      return this.change(tx, c, { status: 'arrived', arrivedAt: now });
    });
  }
  observe(
    userId: string,
    id: string,
    condition: NonNullable<Call['observation']>,
  ) {
    return this.action(userId, id, async (tx, c, now) => {
      if (c.responderId !== userId)
        throw new IncidentError('INCIDENT_ACTION_FORBIDDEN');
      if (c.observation === condition) return c;
      if (c.status !== 'arrived' || c.observation)
        throw new IncidentError('INCIDENT_STATE_CONFLICT');
      const updated = await this.change(tx, c, {
        observation: condition,
        observedAt: now,
      });
      await this.log(tx, c, 'observation', userId, { condition });
      if (userId !== c.foremanId)
        await this.notify(tx, c, c.foremanId, 'observation', now);
      return updated;
    });
  }
  dial(userId: string, id: string, requestId: string) {
    return this.action(userId, id, async (tx, c, now) => {
      const [prior] = await tx
        .select({ id: emergencyDialIntents.id })
        .from(emergencyDialIntents)
        .where(
          and(
            eq(emergencyDialIntents.requestId, requestId),
            eq(emergencyDialIntents.userId, userId),
            eq(emergencyDialIntents.incidentId, id),
          ),
        );
      if (prior) return c;
      if (c.closedAt) throw new IncidentError('INCIDENT_STATE_CONFLICT');
      const inserted = await tx
        .insert(emergencyDialIntents)
        .values({
          incidentId: id,
          siteId: c.siteId,
          userId,
          requestId,
          openedAt: now,
        })
        .onConflictDoNothing()
        .returning({ id: emergencyDialIntents.id });
      if (!inserted.length) throw new IncidentError('REQUEST_ID_CONFLICT');
      await this.log(tx, c, 'dialer_opened', userId);
      return c;
    });
  }
  siteDial(siteId: string, userId: string, requestId: string) {
    return this.database.db.transaction(async (tx) => {
      await siteAccess(tx, siteId, userId);
      const [entry] = await tx
        .insert(emergencyDialIntents)
        .values({ siteId, userId, requestId, openedAt: this.now() })
        .onConflictDoNothing()
        .returning();
      if (entry) {
        await appendEvent(tx, {
          siteId,
          actorId: userId,
          subjectId: userId,
          entityId: entry.id,
          type: 'incident.dialer_opened',
          source: 'human',
        });
        return { id: entry.id, openedAt: entry.openedAt };
      }
      const [prior] = await tx
        .select()
        .from(emergencyDialIntents)
        .where(
          and(
            eq(emergencyDialIntents.siteId, siteId),
            eq(emergencyDialIntents.userId, userId),
            eq(emergencyDialIntents.requestId, requestId),
          ),
        );
      if (!prior || prior.incidentId)
        throw new IncidentError('REQUEST_ID_CONFLICT');
      return { id: prior.id, openedAt: prior.openedAt };
    });
  }
  close(userId: string, id: string, input: ResolutionInput) {
    return this.action(userId, id, async (tx, c, now) => {
      if (c.responderId !== userId)
        throw new IncidentError('INCIDENT_ACTION_FORBIDDEN');
      if (
        c.closedAt &&
        c.resolution === input.reason &&
        c.photoId === (input.photoId ?? null)
      )
        return c;
      if (c.status !== 'arrived' || !c.arrivedAt || !c.observedAt)
        throw new IncidentError('INCIDENT_STATE_CONFLICT');
      if (input.photoId)
        await bindPhoto(tx, {
          photoId: input.photoId,
          siteId: c.siteId,
          ownerId: userId,
          purpose: 'incident_scene',
          notBefore: c.arrivedAt,
          entityId: id,
          viewerIds: [c.workerId, userId],
        });
      const updated = await this.change(tx, c, {
        status: 'closed',
        resolution: input.reason,
        closedAt: now,
        photoId: input.photoId ?? null,
      });
      await this.log(tx, c, 'closed', userId, {
        reason: input.reason,
        photoId: input.photoId ?? null,
      });
      await this.cancelQueuedAlerts(tx, c);
      await this.updateContacts(tx, c, userId, 'closed', now);
      return updated;
    });
  }
  retry(userId: string, id: string) {
    return this.action(userId, id, async (tx, c, now) => {
      if (c.workerId !== userId && !(await this.foreman(tx, c, userId)))
        throw new IncidentError('INCIDENT_ACTION_FORBIDDEN');
      if (c.status === 'calling') return c;
      if (c.status !== 'unanswered')
        throw new IncidentError('INCIDENT_STATE_CONFLICT');
      const available = await tx
        .select()
        .from(incidentRecipients)
        .where(
          and(
            eq(incidentRecipients.incidentId, id),
            isNull(incidentRecipients.declinedAt),
          ),
        );
      const next = await this.nextCandidate(tx, c, now);
      if (!available.length) return next;
      await tx
        .update(incidentPushes)
        .set({
          status: 'queued',
          attempts: 0,
          requestedAt: now,
          nextAttemptAt: now,
        })
        .where(
          and(
            eq(incidentPushes.incidentId, id),
            inArray(
              incidentPushes.userId,
              available.map((r) => r.userId),
            ),
            eq(incidentPushes.type, 'alert'),
            inArray(incidentPushes.status, ['unavailable', 'failed', 'sent']),
          ),
        );
      await this.log(tx, c, 'retry', userId);
      return this.change(tx, next, {
        status: 'calling',
        nextCallAt: new Date(now.getTime() + responderWait),
      });
    });
  }
  async processDue() {
    const db = this.database.db,
      now = this.now();
    const due = await db
      .select({ id: incidents.id })
      .from(incidents)
      .where(
        or(
          and(
            eq(incidents.status, 'awaiting_worker'),
            lte(incidents.workerDueAt, now),
          ),
          and(eq(incidents.status, 'calling'), lte(incidents.nextCallAt, now)),
          and(
            inArray(incidents.status, ['responding', 'arrived']),
            isNull(incidents.observedAt),
            lte(
              incidents.acceptedAt,
              new Date(now.getTime() - verificationWait),
            ),
            notExists(
              db
                .select({ id: incidentPushes.id })
                .from(incidentPushes)
                .where(
                  and(
                    eq(incidentPushes.incidentId, incidents.id),
                    eq(incidentPushes.type, 'verification_overdue'),
                  ),
                ),
            ),
          ),
        ),
      )
      .orderBy(asc(incidents.createdAt))
      .limit(100);
    for (const { id } of due)
      await db.transaction(async (tx) => {
        const [c] = await tx
          .select()
          .from(incidents)
          .where(eq(incidents.id, id))
          .for('no key update', { skipLocked: true });
        if (c) await this.advance(tx, c, this.now());
      });
  }
  async dispatchPending() {
    if (this.stopping) return;
    const pending = await this.database.db
      .select({ id: incidentPushes.id, incidentId: incidentPushes.incidentId })
      .from(incidentPushes)
      .where(
        and(
          eq(incidentPushes.status, 'queued'),
          lte(incidentPushes.nextAttemptAt, this.now()),
        ),
      )
      .orderBy(asc(incidentPushes.nextAttemptAt), asc(incidentPushes.id))
      .limit(8);
    await Promise.all(pending.map((job) => this.dispatch(job)));
  }
  private async dispatch(job: { id: string; incidentId: string }) {
    if (this.stopping) return;
    await this.database.db.transaction(async (tx) => {
      const [c] = await tx
        .select()
        .from(incidents)
        .where(eq(incidents.id, job.incidentId))
        .for('share', { skipLocked: true });
      if (!c) return;
      const [p] = await tx
        .select()
        .from(incidentPushes)
        .where(eq(incidentPushes.id, job.id))
        .for('update', { skipLocked: true });
      const now = this.now();
      if (!p || p.status !== 'queued' || p.nextAttemptAt > now) return;
      let status: Push['status'] = 'failed';
      const attempts = p.attempts + 1;
      const ttl =
        p.requestedAt.getTime() +
        (p.type === 'alert' ? responderWait : 60_000) -
        now.getTime();
      if (p.type === 'verification_overdue' && (c.observedAt || c.closedAt))
        status = 'cancelled';
      else if (
        p.type === 'alert' &&
        (c.closedAt ||
          (c.responderId && p.userId !== c.foremanId) ||
          !(await this.contactEligible(tx, c, p.userId, now)))
      )
        status = 'cancelled';
      else if (ttl <= 0) status = 'unavailable';
      else {
        const [preference] = await tx
          .select()
          .from(notificationPreferences)
          .where(eq(notificationPreferences.userId, p.userId));
        const devices = await tx
          .select({ token: pushDevices.fcmToken })
          .from(pushDevices)
          .where(eq(pushDevices.userId, p.userId));
        if (preference?.emergencyPush === false || !devices.length)
          status = 'unavailable';
        else {
          const result = await this.sender
            .send(
              devices.map((d) => d.token),
              {
                eventId: p.id,
                siteId: c.siteId,
                incidentId: c.id,
                type: 'incident_' + p.type,
              },
              ttl,
            )
            .catch(() => ({ accepted: false, invalidTokens: [] as string[] }));
          if (result.invalidTokens.length)
            await tx
              .delete(pushDevices)
              .where(
                and(
                  eq(pushDevices.userId, p.userId),
                  inArray(pushDevices.fcmToken, result.invalidTokens),
                ),
              );
          status = result.accepted
            ? 'sent'
            : attempts < 3
              ? 'queued'
              : 'failed';
        }
      }
      const finishedAt = this.now();
      await tx
        .update(incidentPushes)
        .set({
          status,
          attempts,
          sentAt: status === 'sent' ? finishedAt : p.sentAt,
          nextAttemptAt: new Date(finishedAt.getTime() + attempts * 2000),
        })
        .where(eq(incidentPushes.id, p.id));
      if (status === 'sent' && p.type === 'alert') {
        await tx
          .update(incidentRecipients)
          .set({ sentAt: finishedAt })
          .where(
            and(
              eq(incidentRecipients.incidentId, c.id),
              eq(incidentRecipients.userId, p.userId),
              isNull(incidentRecipients.sentAt),
            ),
          );
      }
    });
  }
  stopDispatch() {
    this.stopping = true;
  }
}
