import type { PatToolEntry, PatToolGroup } from './types';
import type { MultiSelectOption } from '../components/MultiSelectDropdown';

/** The reserved name of the REST data edge on the tools axis (`TokenRestriction.RestTool`). */
export const REST_TOOL = 'rest';

/** The picker's groups, in the order the form shows them. */
export const TOOL_GROUPS: { group: PatToolGroup; caption: string }[] = [
  { group: 'data', caption: 'Data tools' },
  { group: 'admin', caption: 'Admin tools' },
  { group: 'surface', caption: 'Other surfaces' },
];

/** How a name reads in the picker: an MCP tool by its name, a surface as "label (name)", so
  * `rest` reads "REST data edge (rest)". */
export function toolLabel(entry: PatToolEntry): string {
  return entry.group === 'surface' && entry.label !== entry.name
    ? `${entry.label} (${entry.name})`
    : entry.name;
}

/** The catalog entries of one group as picker options, in the server's order. */
export function toolOptions(catalog: PatToolEntry[], group: PatToolGroup): MultiSelectOption[] {
  return catalog
    .filter(e => e.group === group)
    .map(e => ({ value: e.name, label: toolLabel(e), title: e.description }));
}

/** The picked names of one group, kept in the catalog's order. */
export function pickedIn(catalog: PatToolEntry[], group: PatToolGroup, picked: string[]): string[] {
  return catalog.filter(e => e.group === group && picked.includes(e.name)).map(e => e.name);
}

/** `picked` with the names of `group` replaced by `values`, the other groups kept. */
export function replaceGroup(
  catalog: PatToolEntry[],
  group: PatToolGroup,
  picked: string[],
  values: string[],
): string[] {
  const inGroup = new Set(catalog.filter(e => e.group === group).map(e => e.name));
  return [...picked.filter(n => !inGroup.has(n)), ...values];
}

/** The `tools` field of a create request: `undefined` (an omitted key, unrestricted) when nothing
  * is picked, since `[]` would mint a token that reaches no tool at all. */
export function toolsRequest(picked: string[]): string[] | undefined {
  return picked.length > 0 ? [...picked] : undefined;
}

/** True when the picks narrow the token to some MCP tools but leave out `rest`: such a token is
  * refused on the REST data edge, which an unrestricted token reaches. Nothing picked is
  * unrestricted, so no note. */
export function missesRest(catalog: PatToolEntry[], picked: string[]): boolean {
  if (picked.includes(REST_TOOL)) return false;
  const mcp = new Set(catalog.filter(e => e.group !== 'surface').map(e => e.name));
  return picked.some(n => mcp.has(n));
}
