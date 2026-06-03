import { useParams, useNavigate } from 'react-router-dom';
import Breadcrumb from '../components/Breadcrumb';
import PoolDetailBody from '../components/PoolDetailBody';

export default function PoolDetail() {
  const { tenant, tenantDb, pool } = useParams<{ tenant: string; tenantDb: string; pool: string }>();
  const navigate = useNavigate();

  if (!tenant || !tenantDb || !pool) {
    return <p style={{ color: 'red' }}>Missing tenant / tenantDb / pool in URL.</p>;
  }

  return (
    <div>
      <Breadcrumb
        items={[
          { label: 'Tenants', to: '/tenants' },
          { label: tenant,    to: `/tenant/${encodeURIComponent(tenant)}` },
          { label: tenantDb },
          { label: pool },
        ]}
      />
      <PoolDetailBody
        tenant={tenant}
        tenantDb={tenantDb}
        pool={pool}
        onStopped={() => navigate(`/tenant/${encodeURIComponent(tenant)}`)}
      />
    </div>
  );
}
