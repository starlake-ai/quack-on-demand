import { Fragment } from 'react';
import { nodeIdParts } from './nodeIdParts';

/** A node id in the measured face that wraps only after a `--` separator. Each part is
 *  unbreakable (so `acme-sales` or the `--` itself never splits) and a <wbr> follows it;
 *  <wbr> adds no character, so a copied id is unchanged. */
export default function NodeId({ id }: { id: string }) {
  const parts = nodeIdParts(id);
  return (
    <code className="node-id">
      {parts.map((p, i) => (
        <Fragment key={i}>
          <span>{p}</span>
          {i < parts.length - 1 && <wbr />}
        </Fragment>
      ))}
    </code>
  );
}
