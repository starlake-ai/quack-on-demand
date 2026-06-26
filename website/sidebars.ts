import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docs: [
    { type: 'doc', id: 'introduction', label: 'Introduction' },
    {
      type: 'category',
      label: 'Getting Started',
      items: ['getting-started/quickstart', 'getting-started/install', 'getting-started/demo'],
    },
    {
      type: 'category',
      label: 'Concepts',
      items: [
        'concepts/architecture',
        'concepts/tenancy',
        'concepts/routing',
        'concepts/sessions-transactions',
        'concepts/catalogs',
        'concepts/state-storage',
      ],
    },
    {
      type: 'category',
      label: 'Operating',
      items: [
        {
          type: 'category',
          label: 'Deployment',
          items: [
            'operating/deploy-local',
            'operating/deploy-docker',
            'operating/deploy-kubernetes',
            'operating/tls',
            'operating/resilience',
          ],
        },
        {
          type: 'category',
          label: 'Provisioning',
          items: [
            'operating/tenants-databases',
            'operating/pools-cohorts',
            'operating/federation',
          ],
        },
        {
          type: 'category',
          label: 'Identity & access',
          items: [
            'operating/authentication',
            'operating/auth-providers',
            'operating/oauth-server-setup',
            'operating/rbac-model',
            'operating/rbac-admin',
          ],
        },
        'operating/observability',
        'operating/manifest',
        'operating/admin-ui',
      ],
    },
    {
      type: 'category',
      label: 'Connecting',
      items: [
        'connecting/clients',
        'connecting/authenticating',
        'connecting/sql',
        'connecting/powerbi',
        'connecting/tableau',
      ],
    },
    {
      type: 'category',
      label: 'Reference',
      items: [
        'reference/configuration',
        { type: 'link', label: 'REST API', href: 'pathname:///api/' },
        'reference/cli',
        'reference/metrics',
      ],
    },
    {
      type: 'category',
      label: 'Contributing',
      items: [
        'contributing/dev-loop',
        'contributing/architecture-map',
        'contributing/extending',
      ],
    },
  ],
};

export default sidebars;
