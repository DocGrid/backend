export type ApiEnvelope<T> = {
  success: boolean;
  status: number;
  data: T;
  timestamp: string;
};

export type ApiErrorBody = {
  success: false;
  status: number;
  code?: string;
  message?: string;
  method?: string;
  path?: string;
  timestamp?: string;
};

export type PageResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
};

export type Department = { id: number; name: string; code: string };

export type Role = { id: number; name: string; code: string };

export type LoginResponse = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  userId: number;
  email: string;
  roles: string[];
};

export type MeResponse = {
  userId: number;
  email: string;
  name: string;
  nickname: string | null;
  profileImageUrl: string | null;
  departmentId: number | null;
  departmentName: string | null;
  roles: string[];
  status: string;
  createdAt: string;
  lastLoginAt: string | null;
};

export type DocumentSummary = {
  documentId: number;
  title: string;
  description: string | null;
  documentType: string;
  status: string;
  visibility: string;
  ownerUserId: number;
  currentVersionNo: number | null;
  currentVersionStatus: string | null;
  createdAt: string;
  updatedAt: string;
};

export type CurrentDocumentVersion = {
  documentVersionId: number;
  versionNo: number;
  status: string;
  originalFilename: string;
  contentType: string;
  fileSize: number;
  indexedAt: string | null;
  createdAt: string;
};

export type DocumentDetail = {
  documentId: number;
  title: string;
  description: string | null;
  documentType: string;
  sourceType: string;
  status: string;
  visibility: string;
  ownerUserId: number;
  ownerName: string;
  currentVersion: CurrentDocumentVersion | null;
  contentAvailable: boolean;
  createdAt: string;
  updatedAt: string;
};

export type UpdateDocumentMetadataRequest = {
  title: string;
  description: string | null;
};

export type DocumentContent = {
  documentId: number;
  documentVersionId: number;
  versionNo: number;
  content: string;
  chunkCount: number;
};

export type DocumentStatus = {
  documentId: number;
  documentStatus: string;
  currentVersion: { versionNo: number; status: string } | null;
  processingVersion: { versionNo: number; status: string; jobStatus: string } | null;
};

export type DocumentUploadResponse = {
  documentId: number;
  documentVersionId: number;
  fileObjectId: number;
  embeddingJobId: number;
  documentStatus: string;
  jobStatus: string;
};

export type DocumentVersionUploadResponse = {
  documentId: number;
  documentVersionId: number;
  versionNo: number;
  embeddingJobId: number;
  currentVersionId: number | null;
  documentStatus: string;
  versionStatus: string;
  jobStatus: string;
};

export type SearchResult = {
  rank: number;
  documentId: number;
  chunkId: number;
  documentTitle: string;
  chunkText: string;
  pageNo: number | null;
  similarityScore: number;
};

export type Citation = {
  label: string;
  documentId: number;
  documentTitle: string;
  chunkId: number;
  pageNo: number | null;
  quotedText: string;
};

export type RagStatus = "PROCESSING" | "SUCCESS" | "FAILED";

export type SearchResponse = {
  queryId: number;
  results: SearchResult[];
  ragStatus: RagStatus;
  answer: string | null;
  citations: Citation[];
};

export type Collection = {
  collectionId: number;
  name: string;
  description: string | null;
  ownerUserId: number;
  parentCollectionId: number | null;
  visibility: string;
  status: string;
  createdAt: string;
};

export type CollectionDocument = {
  collectionId: number;
  document: DocumentSummary;
  addedBy: number | null;
  addedAt: string;
};

export type PermissionSummary = {
  documentId: number;
  canRead: boolean;
  canWrite: boolean;
  canAdmin: boolean;
  sources: string[];
};

export type PermissionGrant = {
  permissionId: number;
  documentId?: number;
  collectionId?: number;
  targetType: string;
  userId: number | null;
  roleId: number | null;
  departmentId: number | null;
  permissionType: string;
  canRead: boolean;
  canWrite: boolean;
  canAdmin: boolean;
  grantedBy: number;
  grantedAt: string;
  expiresAt: string | null;
};

export type McpToken = {
  tokenId: number;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
};

export type McpTokenIssue = {
  tokenId: number;
  token: string;
  message: string;
  createdAt: string;
};

export type DashboardSummary = {
  documents: { total: number; searchable: number; pendingIndex: number };
  jobs: { pending: number; processing: number; failed: number; avgProcessMs: number | null };
  workers: { activeCount: number; totalCount: number };
  search: { recent24hCount: number };
};

export type ManualRetryEligibility =
  | "ELIGIBLE"
  | "JOB_NOT_FAILED"
  | "VERSION_NOT_FAILED"
  | "DOCUMENT_DELETED"
  | "DOCUMENT_STATUS_INVALID"
  | "CURRENT_VERSION_INCONSISTENT"
  | "SUPERSEDED_VERSION"
  | "LIVE_JOB_EXISTS"
  | "DATA_INCONSISTENT";

export type IndexingJob = {
  jobId: number;
  status: string;
  manualRetryEligibility: ManualRetryEligibility;
  priority: number;
  retryCount: number;
  maxRetryCount: number;
  nextRetryAt: string | null;
  documentId: number;
  documentTitle: string;
  documentVersionId: number;
  documentVersionNo: number;
  documentVersionStatus: string;
  embeddingModelId: number;
  embeddingModelName: string;
  embeddingModelVersion: string;
  workerId: number | null;
  workerName: string | null;
  errorCode: string | null;
  lockedAt: string | null;
  lockExpiresAt: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  failedAt: string | null;
};

export type RetryAllJobsResult = {
  scannedCount: number;
  retriedCount: number;
  skippedCount: number;
  failedCount: number;
  message: string;
};

export type IndexingAttempt = {
  attemptId: number;
  attemptNo: number;
  status: string;
  workerId: number | null;
  workerName: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  errorCode: string | null;
};

export type IndexingEvent = {
  eventId: number;
  eventType: string;
  fromStatus: string | null;
  toStatus: string | null;
  message: string | null;
  occurredAt: string;
};

export type Worker = {
  workerId: number;
  workerName: string;
  instanceId: string;
  hostName: string;
  ipAddress: string;
  status: string;
  lastHeartbeatAt: string;
  startedAt: string;
  stoppedAt: string | null;
};

export type SyncEventSummary = {
  pendingCount: number;
  processingCount: number;
  failedCount: number;
  oldestPendingAgeSeconds: number | null;
  processedLast24hCount: number;
  failedLast24hCount: number;
  retriedLast24hCount: number;
  successRateLast24h: number;
  lastProcessedEventId: string | null;
  lastProcessedAt: string | null;
};

export type SyncIssueSummary = {
  openCount: number;
  repairingCount: number;
  autoResolvedLast24hCount: number;
  failedRepairCount: number;
};

export type SyncReconciliationSummary = {
  runId: string;
  mode: string;
  status: string;
  startCursor: number;
  endCursor: number;
  scannedCount: number;
  detectedCount: number;
  repairRequestedCount: number;
  startedAt: string;
  completedAt: string | null;
  errorCode: string | null;
} | null;

export type SyncAdminSummary = {
  capturedAt: string;
  events: SyncEventSummary;
  issues: SyncIssueSummary;
  reconciliation: SyncReconciliationSummary;
};

export type SyncEventAdmin = {
  eventId: string;
  idempotencyKey: string;
  aggregateType: string;
  aggregateId: number | null;
  aggregateVersion: number | null;
  eventType: string;
  status: string;
  occurredAt: string;
  availableAt: string;
  processedAt: string | null;
  retryCount: number;
  maxRetryCount: number;
  lockedBy: string | null;
  lockExpiresAt: string | null;
  lastErrorCode: string | null;
};

export type SyncIssueAdmin = {
  issueId: number;
  issueKey: string;
  issueType: string;
  severity: string;
  status: string;
  documentId: number | null;
  documentVersionId: number | null;
  embeddingModelId: number | null;
  expectedJson: string | null;
  actualJson: string | null;
  repairable: boolean;
  detectedAt: string;
  lastDetectedAt: string;
  repairEventId: string | null;
  repairAttemptCount: number;
  resolvedAt: string | null;
  resolutionMessage: string | null;
};

export type UserRoleResponse = {
  userId: number;
  email: string;
  name: string;
  roles: string[];
};

export type AdminUser = {
  userId: number;
  name: string;
  email: string;
  nickname: string | null;
  departmentId: number | null;
  departmentName: string | null;
  status: string;
  roles: string[];
  lastLoginAt: string | null;
  createdAt: string;
};
