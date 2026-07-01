import {
	IDataObject,
	IExecuteFunctions,
	ILoadOptionsFunctions,
	INodeExecutionData,
	INodePropertyOptions,
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
		subtitle: '={{ $parameter["operation"] }}',
		description: 'Run SQL and browse the catalog on a Quack on Demand FlightSQL edge',
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
					{
						name: 'List Catalogs',
						value: 'listCatalogs',
						description: 'List catalogs via the FlightSQL GetCatalogs command',
						action: 'List catalogs',
					},
					{
						name: 'List Schemas',
						value: 'listSchemas',
						description: 'List schemas via the FlightSQL GetDbSchemas command',
						action: 'List schemas',
					},
					{
						name: 'List Tables',
						value: 'listTables',
						description: 'List tables via the FlightSQL GetTables command',
						action: 'List tables',
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
				displayOptions: { show: { operation: ['executeQuery'] } },
			},
			{
				displayName: 'Schema Name or ID',
				name: 'schema',
				type: 'options',
				typeOptions: { loadOptionsMethod: 'getSchemas' },
				default: '',
				description:
					'Schema to filter by. Choose from the list, or specify an ID using an <a href="https://docs.n8n.io/code/expressions/">expression</a>. Leave empty for all schemas.',
				displayOptions: { show: { operation: ['listTables'] } },
			},
			{
				displayName: 'Schema Filter',
				name: 'schemaPattern',
				type: 'string',
				default: '',
				placeholder: 'tpch%',
				description: 'Optional LIKE pattern to filter schema names. Leave empty for all schemas.',
				displayOptions: { show: { operation: ['listSchemas'] } },
			},
			{
				displayName: 'Table Filter',
				name: 'tablePattern',
				type: 'string',
				default: '',
				placeholder: 'cust%',
				description: 'Optional LIKE pattern to filter table names. Leave empty for all tables.',
				displayOptions: { show: { operation: ['listTables'] } },
			},
		],
	};

	methods = {
		loadOptions: {
			// Populate the "Schema" dropdown for List Tables by asking the edge for
			// its schemas over the FlightSQL GetDbSchemas command.
			async getSchemas(this: ILoadOptionsFunctions): Promise<INodePropertyOptions[]> {
				const client = await QodClient.connect(await credentialsToConfig(this));
				try {
					const rows = await client.getSchemas();
					return rows
						.map((r) => String(r.db_schema_name ?? ''))
						.filter((name) => name.length > 0)
						.map((name) => ({ name, value: name }));
				} finally {
					client.close();
				}
			},
		},
	};

	async execute(this: IExecuteFunctions): Promise<INodeExecutionData[][]> {
		const items = this.getInputData();
		const operation = this.getNodeParameter('operation', 0) as string;
		const client = await QodClient.connect(await credentialsToConfig(this));
		const out: INodeExecutionData[] = [];

		const emit = (rows: Array<Record<string, unknown>>, item: number) => {
			for (const row of rows) {
				out.push({ json: row as IDataObject, pairedItem: { item } });
			}
		};

		try {
			// The catalog operations take no per-item input, so run them once.
			if (operation !== 'executeQuery') {
				try {
					if (operation === 'listCatalogs') {
						emit(await client.getCatalogs(), 0);
					} else if (operation === 'listSchemas') {
						const schemaPattern = (this.getNodeParameter('schemaPattern', 0) as string) || undefined;
						emit(await client.getSchemas({ schemaPattern }), 0);
					} else if (operation === 'listTables') {
						const schemaPattern = (this.getNodeParameter('schema', 0) as string) || undefined;
						const tablePattern = (this.getNodeParameter('tablePattern', 0) as string) || undefined;
						emit(await client.getTables({ schemaPattern, tablePattern }), 0);
					}
				} catch (error) {
					if (!this.continueOnFail()) {
						throw new NodeOperationError(this.getNode(), error as Error);
					}
					out.push({ json: { error: (error as Error).message } });
				}
				return [out];
			}

			// Execute Query: run the statement once per input item.
			for (let i = 0; i < items.length; i++) {
				const sql = this.getNodeParameter('query', i) as string;
				try {
					emit(await client.query(sql), i);
				} catch (error) {
					if (this.continueOnFail()) {
						out.push({ json: { error: (error as Error).message }, pairedItem: { item: i } });
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

async function credentialsToConfig(
	ctx: IExecuteFunctions | ILoadOptionsFunctions,
): Promise<QodConfig> {
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