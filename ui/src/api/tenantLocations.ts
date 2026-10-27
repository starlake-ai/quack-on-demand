import type { AddTenantLocationRequest, TenantLocationChangeResponse } from './types';

/** The add-location form. The secret is write-only: never prefilled from a stored location. */
export interface LocationForm {
  location: string;
  accessKeyId: string;
  secretAccessKey: string;
  region: string;
  endpoint: string;
  urlStyle: string;
}

export const EMPTY_LOCATION_FORM: LocationForm = {
  location: '', accessKeyId: '', secretAccessKey: '', region: '', endpoint: '', urlStyle: '',
};

/** 's3' for an s3:// URL, 'local' for an absolute folder, null otherwise. The manager decides
  * the rest (a bucket only, the tenant's folder): this only picks which fields the form shows. */
export function locationKind(location: string): 's3' | 'local' | null {
  const l = location.trim();
  if (l.startsWith('s3://')) return 's3';
  if (l.startsWith('/')) return 'local';
  return null;
}

/** The add request for `form`: credentials only for an s3 location, blank fields left out. */
export function addLocationRequest(tenant: string, form: LocationForm): AddTenantLocationRequest {
  const location = form.location.trim();
  if (locationKind(location) !== 's3') return { tenant, location };
  const pairs: [string, string][] = [
    ['s3_access_key_id', form.accessKeyId],
    ['s3_secret_access_key', form.secretAccessKey],
    ['s3_region', form.region],
    ['s3_endpoint', form.endpoint],
    ['s3_url_style', form.urlStyle],
  ];
  const objectStore = Object.fromEntries(pairs.filter(([, v]) => v.trim() !== ''));
  return { tenant, location, objectStore };
}

/** How a change reached the tenant's running nodes, or null when none was running. */
export function nodesNote(res: Pick<TenantLocationChangeResponse, 'nodesUpdated' | 'nodesFailed'>): string | null {
  if (res.nodesUpdated === 0 && res.nodesFailed.length === 0) return null;
  const applied = `Applied on ${res.nodesUpdated} running node(s).`;
  return res.nodesFailed.length === 0
    ? applied
    : `${applied} Not applied on ${res.nodesFailed.join(', ')}: it applies at the node's next restart.`;
}
