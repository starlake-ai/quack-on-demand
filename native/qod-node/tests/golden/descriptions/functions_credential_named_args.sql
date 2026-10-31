SELECT * FROM read_csv('@DIR@/in.csv', columns = {'qod-not-a-real-password': 'VARCHAR'})
