/**
 * ESLint config for the n8n community node, using eslint-plugin-n8n-nodes-base
 * so the package meets n8n's node/credential conventions before publishing.
 */
module.exports = {
	root: true,
	env: { node: true, es2021: true },
	parser: '@typescript-eslint/parser',
	parserOptions: {
		sourceType: 'module',
		extraFileExtensions: ['.json'],
	},
	ignorePatterns: ['.eslintrc.js', 'scripts/**', '**/*.js', '**/node_modules/**', 'dist/**'],
	overrides: [
		{
			files: ['package.json'],
			plugins: ['eslint-plugin-n8n-nodes-base'],
			extends: ['plugin:n8n-nodes-base/community'],
			rules: {
				'n8n-nodes-base/community-package-json-name-still-default': 'error',
				// This package is Apache-2.0 (matching the parent repo), not the MIT
				// the plugin nudges community nodes toward.
				'n8n-nodes-base/community-package-json-license-not-default': 'off',
			},
		},
		{
			files: ['./credentials/**/*.ts'],
			plugins: ['eslint-plugin-n8n-nodes-base'],
			extends: ['plugin:n8n-nodes-base/credentials'],
			rules: {
				// documentationUrl is a full HTTPS URL (which the companion
				// cred-class-field-documentation-url-not-http-url rule requires). The
				// -miscased rule contradicts it by camel-casing valid URLs, so disable it.
				'n8n-nodes-base/cred-class-field-documentation-url-miscased': 'off',
			},
		},
		{
			files: ['./nodes/**/*.ts'],
			plugins: ['eslint-plugin-n8n-nodes-base'],
			extends: ['plugin:n8n-nodes-base/nodes'],
		},
	],
};