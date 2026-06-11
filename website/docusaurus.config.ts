import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const gaId = process.env.QOD_DOCS_GA_ID ?? '';

const config: Config = {
  title: 'Quack on Demand',
  tagline: 'Multi-tenant Arrow Flight SQL gateway for DuckDB + DuckLake',
  favicon: 'img/favicon.ico',
  url: 'https://qod.starlake.ai',
  baseUrl: '/',
  organizationName: 'starlake-ai',
  projectName: 'quack-on-demand',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',
  i18n: { defaultLocale: 'en', locales: ['en'] },

  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/starlake-ai/quack-on-demand/tree/main/website/',
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
        ...(gaId ? { gtag: { trackingID: gaId, anonymizeIP: true } } : {}),
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/social-card.png',
    navbar: {
      title: 'Quack on Demand',
      logo: {
        alt: 'Quack on Demand',
        src: 'img/mark-light.svg',
        srcDark: 'img/mark-dark.svg',
      },
      items: [
        { type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Docs' },
        { to: 'pathname:///api/', label: 'API', position: 'left' },
        { href: 'https://discord.gg/xHj9D6Rebp', label: 'Discord', position: 'right' },
        { href: 'https://github.com/starlake-ai/quack-on-demand', label: 'GitHub', position: 'right' },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Community',
          items: [
            { label: 'Discord', href: 'https://discord.gg/xHj9D6Rebp' },
            { label: 'GitHub', href: 'https://github.com/starlake-ai/quack-on-demand' },
          ],
        },
      ],
      copyright: `Copyright (c) ${new Date().getFullYear()} Starlake. Apache-2.0.`,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
