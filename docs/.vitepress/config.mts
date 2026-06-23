import { defineConfig } from 'vitepress'

export default defineConfig({
  title: "EnhancedEchest",
  description: "Database-backed multi-chest ender chest plugin for Minecraft",
  // GitHub project page is served from /EnhancedEchest/. If you later point a custom
  // domain at the site (add public/CNAME), change this back to '/'.
  base: '/EnhancedEchest/',
  cleanUrls: true,
  head: [
    // Entries in `head` are emitted verbatim, so the `base` is NOT prepended
    // automatically the way it is for themeConfig.logo / markdown links. The
    // href must therefore include the base path, otherwise on the GitHub Pages
    // project site the favicon resolves to the domain root and 404s.
    ['link', { rel: 'icon', type: 'image/png', href: '/EnhancedEchest/logo.png' }],
    ['link', { rel: 'apple-touch-icon', href: '/EnhancedEchest/logo.png' }],
  ],
  themeConfig: {
    logo: '/logo.png',

    nav: [
      { text: 'Home', link: '/' },
      { text: 'Download', link: '/docs/download' },
      { text: 'Docs', link: '/docs/' }
    ],

    sidebar: [
      {
        text: 'General',
        items: [
          { text: 'Welcome', link: '/docs/' },
          { text: 'Features', link: '/docs/features' },
        ]
      },
      {
        text: 'Getting Started',
        items: [
          { text: 'Download', link: '/docs/download' },
          { text: 'Installation', link: '/docs/installation' }
        ]
      },
      {
        text: 'Documentation',
        items: [
          {
            text: 'Commands & Permissions',
            collapsed: false,
            items: [
              { text: 'Commands', link: '/docs/commands' },
              { text: 'Permissions', link: '/docs/permissions' }
            ]
          },
          {
            text: 'Configuration',
            collapsed: false,
            items: [
              { text: 'Main Config', link: '/docs/configuration' },
              { text: 'Database', link: '/docs/database' },
              { text: 'Migration', link: '/docs/migration' },
              { text: 'Language', link: '/docs/language' }
            ]
          }
        ]
      }
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/OpenVdra/EnhancedEchest' }
    ],

    search: {
      provider: 'local'
    },

    editLink: {
      pattern: 'https://github.com/OpenVdra/EnhancedEchest/edit/main/docs/:path',
      text: 'Edit this page on GitHub'
    }
  }
})
