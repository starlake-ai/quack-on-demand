import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docs: [
    { type: 'doc', id: 'introduction', label: 'Introduction' },
    {
      type: 'category',
      label: 'Getting Started',
      items: ['getting-started/quickstart', 'getting-started/install'],
    },
    {
      type: 'category',
      label: 'Concepts',
      items: ['concepts/architecture'],
    },
    {
      type: 'category',
      label: 'Operating',
      items: [
        'operating/deploy-local',
        'operating/deploy-kubernetes',
        'operating/tls',
        'operating/resilience',
      ],
    },
    {
      type: 'category',
      label: 'Connecting',
      items: ['connecting/clients'],
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
      items: ['contributing/dev-loop'],
    },
  ],
};

export default sidebars;
