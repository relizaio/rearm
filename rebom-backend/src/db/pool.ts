const pg = require('pg');

export const pool = new pg.Pool({
    user: process.env.POSTGRES_USER ? process.env.POSTGRES_USER : `postgres`,
    host: process.env.POSTGRES_HOST ? process.env.POSTGRES_HOST : `localhost`,
    database: process.env.POSTGRES_DATABASE ? process.env.POSTGRES_DATABASE : `postgres`,
    password: process.env.POSTGRES_PASSWORD ? process.env.POSTGRES_PASSWORD : `password`,
    port: process.env.POSTGRES_PORT ? parseInt(process.env.POSTGRES_PORT) : 5438,
    ssl: (process.env.POSTGRES_SSL === 'true') ? true : false
});
