import {
	IDataObject,
	IExecuteFunctions,
	INodeExecutionData,
	INodeType,
	INodeTypeDescription,
	NodeOperationError,
} from 'n8n-workflow';

import { QodClient, QodConfig } from './QodClient';

export class QuackOnDemand implements INodeType {
	description: INodeTypeDescription = {
		displayName: 'Quack on Demand',
		name: 'quackOnDemand',
		icon: 'file:qod.svg',
		group: ['transform'],
		version: 1,
		subtitle: '={{ "Execute query" }}',
		description: 'Run SQL against a Quack on Demand FlightSQL edge',
		defaults: { name: 'Quack on Demand' },
		inputs: ['main'],
		outputs: ['main'],
		credentials: [{ name: 'quackOnDemandApi', required: true }],
		properties: [
			{
				displayName: 'Operation',
				name: 'operation',
				type: 'options',
				noDataExpression: true,
				options: [
					{
						name: 'Execute Query',
						value: 'executeQuery',
						description: 'Run a SQL statement and return the rows',
						action: 'Execute a SQL query',
					},
				],
				default: 'executeQuery',
			},
			{
				displayName: 'Query',
				name: 'query',
				type: 'string',
				typeOptions: { rows: 5 },
				default: '',
				placeholder: 'SELECT * FROM tpch1.customer LIMIT 100',
				required: true,
				description: 'The SQL statement to run. Runs once per input item.',
			},
		],
	};

	async execute(this: IExecuteFunctions): Promise<INodeExecutionData[][]> {
		const items = this.getInputData();
		const cfg = await credentialsToConfig(this);

		const client = await QodClient.connect(cfg);
		const out: INodeExecutionData[] = [];
		try {
			for (let i = 0; i < items.length; i++) {
				const sql = this.getNodeParameter('query', i) as string;
				try {
					const rows = await client.query(sql);
					for (const row of rows) {
						out.push({ json: row as IDataObject, pairedItem: { item: i } });
					}
				} catch (error) {
					if (this.continueOnFail()) {
						out.push({
							json: { error: (error as Error).message },
							pairedItem: { item: i },
						});
						continue;
					}
					throw new NodeOperationError(this.getNode(), error as Error, { itemIndex: i });
				}
			}
		} finally {
			client.close();
		}

		return [out];
	}
}

async function credentialsToConfig(ctx: IExecuteFunctions): Promise<QodConfig> {
	const c = await ctx.getCredentials('quackOnDemandApi');
	return {
		host: c.host as string,
		port: c.port as number,
		user: c.user as string,
		password: c.password as string,
		tenant: c.tenant as string,
		pool: c.pool as string,
		superuser: c.superuser as boolean,
		tls: c.tls as boolean,
		tlsVerify: c.tlsVerify as boolean,
	};
}