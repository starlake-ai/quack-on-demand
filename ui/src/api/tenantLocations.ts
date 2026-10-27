import type { AddTenantLocationRequest } from './types';

/** True for an absolute folder, the only location a tenant authorizes (S3 is per user). The manager
  * decides the rest (the tenant's folder under the root). */
export function isLocalFolder(location: string): boolean {
  return location.trim().startsWith('/');
}

/** The add request for `location`, trimmed. */
export function addLocationRequest(tenant: string, location: string): AddTenantLocationRequest {
  return { tenant, location: location.trim() };
}
