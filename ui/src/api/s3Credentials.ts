import type { ProfileS3AddRequest } from './types';

/** The add-credential form. The secret and the token are write-only: never prefilled. */
export interface S3Form {
  scope: string;
  accessKeyId: string;
  secretAccessKey: string;
  sessionToken: string;
  region: string;
  endpoint: string;
  urlStyle: string;
}

export const EMPTY_S3_FORM: S3Form = {
  scope: '', accessKeyId: '', secretAccessKey: '', sessionToken: '', region: '', endpoint: '', urlStyle: '',
};

/** The add request for `form`: scope and key id trimmed, the secret as typed, blank optional fields
  * left out. The manager decides the rest (the scope's shape, the endpoint allowlist, data folders). */
export function addS3Request(form: S3Form): ProfileS3AddRequest {
  const req: ProfileS3AddRequest = {
    scope: form.scope.trim(),
    accessKeyId: form.accessKeyId.trim(),
    secretAccessKey: form.secretAccessKey,
  };
  if (form.sessionToken.trim() !== '') req.sessionToken = form.sessionToken;
  if (form.region.trim() !== '') req.region = form.region.trim();
  if (form.endpoint.trim() !== '') req.endpoint = form.endpoint.trim();
  if (form.urlStyle !== '') req.urlStyle = form.urlStyle;
  return req;
}

/** A stored endpoint for a listing: AWS for none, else the host and its transport. */
export function describeEndpoint(endpoint: string | null | undefined): string {
  if (endpoint == null || endpoint === '') return 'AWS';
  return endpoint.startsWith('http://')
    ? `${endpoint.slice('http://'.length)} (plain HTTP)`
    : `${endpoint} (TLS)`;
}
