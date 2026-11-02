import { fileURLToPath } from 'node:url';
import PDFDocument from 'pdfkit';
import { z } from 'zod';

const format = z.enum(['json', 'csv', 'pdf']).default('json');
const monthInput = z
  .string()
  .regex(/^\d{4}-(0[1-9]|1[0-2])$/)
  .refine(
    (value) =>
      Number(value.slice(0, 4)) >= 1000 && Number(value.slice(0, 4)) < 9999,
  );
export const workQuery = z
  .strictObject({
    month: monthInput.optional(),
    from: z.iso.date().optional(),
    to: z.iso.date().optional(),
    userId: z.uuid().toLowerCase().optional(),
    scope: z.enum(['self', 'all']).default('self'),
    format,
  })
  .refine((q) => q.scope !== 'all' || q.userId === undefined)
  .refine((q) =>
    q.from || q.to
      ? Boolean(
          q.from &&
          q.to &&
          !q.month &&
          q.from <= q.to &&
          Date.parse(q.to) - Date.parse(q.from) <= 365 * 86400000,
        )
      : true,
  );
export const monthlyQuery = z.strictObject({
  month: monthInput.optional(),
  format,
});
export const incidentQuery = z.strictObject({ format });
export const incidentListQuery = z.strictObject({
  month: monthInput.optional(),
  limit: z.coerce.number().int().min(1).max(100).default(30),
  offset: z.coerce.number().int().min(0).max(1_000_000).default(0),
});

const kstDate = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
const kstDateTime = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

export function workPeriod(q: { from?: string; to?: string; month?: string }) {
  if (q.from && q.to) return { from: q.from, to: q.to };
  const month = q.month ?? kstDate.format(new Date()).slice(0, 7);
  const [year, number] = month.split('-').map(Number);
  return {
    from: month + '-01',
    to: new Date(Date.UTC(year, number, 0)).toISOString().slice(0, 10),
  };
}
export function displayTime(value: Date | string | null | undefined) {
  return value ? kstDateTime.format(new Date(value)) : null;
}
type Cell = string | number | null;
export type ReportSection = {
  title: string;
  columns: string[];
  rows: Cell[][];
};
export type ReportFile = {
  title: string;
  generatedAt: Date;
  sections: ReportSection[];
  scenePhoto?: Buffer;
  ppePhotos?: { bytes: Buffer; caption: string }[];
};
export const disclaimer =
  '사람이 확인한 사실과 시스템 추정을 구분한 기록입니다. 사고 원인이나 법률상 의무 이행 여부를 확정하지 않습니다.';
export const wageNote =
  '예상 일당은 작업자가 기록한 금액으로 실제 지급액·수당과 다를 수 있습니다.';
const valueLabels: Record<string, string> = {
  abnormal_movement: '이상 움직임 감지',
  manual_help: '직접 도움 요청',
  okay: '괜찮아요',
  help_needed: '도움 필요',
  buddy: '안전 동료',
  foreman: '현장 책임자',
  zone: '같은 구역 작업자',
  unconscious: '의식이 없어 보임',
  immobile: '움직이기 어려워 보임',
  conscious: '의식 있음',
  appears_safe: '안전해 보임',
  worker_confirmed_okay: '본인이 괜찮다고 응답',
  no_help_needed: '현장에서 도움 불필요 확인',
  onsite_action_completed: '현장 조치 완료',
  handoff_confirmed: '인계 확인',
  pending: '검토 대기',
  accepted: '승인',
  declined: '반려',
};
export function displayValue(value: string | null) {
  return value === null ? null : (valueLabels[value] ?? value);
}

function csvCell(value: Cell) {
  let text = String(value ?? '');
  if (
    typeof value === 'string' &&
    (/^\s*[=+@-]/u.test(text) || /^[\t\r\n]/.test(text))
  )
    text = "'" + text;
  return '"' + text.replaceAll('"', '""') + '"';
}

export function makeCsv(file: ReportFile) {
  const columns = [
    ...new Set(file.sections.flatMap((section) => section.columns)),
  ];
  const records: Cell[][] = [['보고서', '생성 시각(KST)', '구분', ...columns]];
  for (const section of file.sections) {
    const indexes = columns.map((column) => section.columns.indexOf(column));
    for (const row of section.rows)
      records.push([
        file.title,
        displayTime(file.generatedAt),
        section.title,
        ...indexes.map((i) => (i < 0 ? null : (row[i] ?? null))),
      ]);
  }
  return Buffer.from(
    '\uFEFF' +
      records.map((row) => row.map(csvCell).join(',')).join('\r\n') +
      '\r\n',
    'utf8',
  );
}

const fontPath = fileURLToPath(
  new URL('../../assets/fonts/NotoSansKR.ttf', import.meta.url),
);
export function makePdf(file: ReportFile): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const pdf = new PDFDocument({
      size: 'A4',
      margin: 46,
      info: { Title: file.title, Author: 'SafeLoop' },
    });
    const chunks: Buffer[] = [];
    pdf.on('data', (chunk: Buffer) => chunks.push(chunk));
    pdf.on('error', reject);
    pdf.on('end', () => resolve(Buffer.concat(chunks)));
    pdf.font(fontPath).fillColor('#17392e').fontSize(19).text(file.title);
    pdf
      .fontSize(10)
      .fillColor('#394943')
      .text('SafeLoop · 생성 시각(KST) ' + displayTime(file.generatedAt));
    for (const section of file.sections) {
      if (pdf.y > 680) pdf.addPage();
      pdf.moveDown().fontSize(14).fillColor('#17392e').text(section.title);
      pdf.fontSize(9).fillColor('#293d34');
      if (!section.rows.length) pdf.text('해당 기록 없음');
      for (const row of section.rows) {
        const line = section.columns
          .map((column, i) => column + ' ' + String(row[i] ?? '미기록'))
          .join('  ·  ');
        pdf.text(line, { paragraphGap: 5 });
      }
    }
    for (const photo of file.ppePhotos ?? []) {
      pdf.addPage();
      pdf.fontSize(14).text('보호구 교차 확인 사진');
      pdf.moveDown().fontSize(10).text(photo.caption);
      pdf.moveDown();
      pdf.image(photo.bytes, { fit: [460, 480] });
    }
    if (file.scenePhoto) {
      pdf.addPage();
      pdf.moveDown().fontSize(12).text('사고 현장 사진');
      pdf.image(file.scenePhoto, { fit: [460, 220] });
    }
    pdf.end();
  });
}
