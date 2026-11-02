export type PushData = {
  eventId: string;
  recipientId: string;
  type: string;
  incidentId?: string;
  siteId?: string;
  entityId?: string;
};

export type PushResult = {
  accepted: boolean;
  invalidTokens: string[];
};

export type PushJob = {
  credentialsPath: string;
  projectId: string;
  tokens: string[];
  data: PushData;
  expiresAt: number;
};

export type PushProgress = {
  done: boolean;
  result: PushResult;
};
