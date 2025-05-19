import express from 'express';
import { logger } from './logger';
const utils = require('./utils')

const router = express.Router();


  router.get('/restapi/bomById/:uuid', async (req, res) => {
    let bomId = req.params.uuid
    try {
        let retObj = {}
        let byIdRows = await utils.runQuery(`select * from rebom.boms where uuid = $1`, [bomId])
        if (byIdRows && byIdRows.rows && byIdRows.rows[0]) {
            retObj = byIdRows.rows[0].bom
        }
        if (req.query.download) {
          res.type('application/octet-stream')
        }
        res.send(retObj)
    } catch (error) {
        logger.error('Errored bom for id = ' + bomId)
        res.statusCode = 404
        res.send('Bom not found')
    }
  })

  export default router;