import { ICredentialType, INodeProperties } from 'n8n-workflow';

export class QuackOnDemandApi implements ICredentialType {
	name = 'quackOnDemandApi';

	displayName = 'Quack on Demand API';

	documentationUrl = 'https://github.com/starlake-ai/quack-on-demand';

	properties: INodeProperties[] = [
		{
			displayName: 'Host',
			name: 'host',
			type: 'string',
			default: '127.0.0.1',
			description: 'Hostname or IP of the FlightSQL edge',
		},
		{
			displayName: 'Port',
			name: 'port',
			type: 'number',
			default: 31338,
			description: 'FlightSQL edge port',
		},
		{
			displayName: 'Tenant',
			name: 'tenant',
			type: 'string',
			default: 'acme',
			description: 'Tenant used to route the query',
		},
		{
			displayName: 'Pool',
			name: 'pool',
			type: 'string',
			default: 'bi',
			description: 'Pool within the tenant used to route the query',
		},
		{
			displayName: 'User',
			name: 'user',
			type: 'string',
			default: 'admin',
			description: 'Username for HTTP Basic authentication',
		},
		{
			displayName: 'Password',
			name: 'password',
			type: 'string',
			typeOptions: { password: true },
			default: 'admin',
			description: 'Password for HTTP Basic authentication',
		},
		{
			displayName: 'Superuser',
			name: 'superuser',
			type: 'boolean',
			default: true,
			description:
				'Whether to authenticate against the system realm (bypasses the per-statement ACL gate). Tenant and pool still drive routing.',
		},
		{
			displayName: 'Use TLS',
			name: 'tls',
			type: 'boolean',
			default: true,
			description: 'Whether the edge listens with TLS (the default). Turn off for a plaintext edge.',
		},
		{
			displayName: 'Verify TLS Certificate',
			name: 'tlsVerify',
			type: 'boolean',
			default: false,
			displayOptions: { show: { tls: [true] } },
			description:
				'Whether to validate the certificate chain against the system trust store. Leave off to accept the edge auto-generated self-signed certificate; turn on once a CA-signed certificate is installed.',
		},
	];
}