// Only a loopback TLS/HTTP2 supplier fault fixture; no local business rules or real secrets.
import http2 from 'node:http2'
import fs from 'node:fs'

const [pfx, readyFile, countsFile] = process.argv.slice(2)
const counts = {}
const save = () => {
  fs.writeFileSync(`${countsFile}.tmp`, JSON.stringify(counts))
  fs.renameSync(`${countsFile}.tmp`, countsFile)
}
const server = http2.createSecureServer({
  pfx: fs.readFileSync(pfx), passphrase: 'synthetic-test-store', allowHTTP1: false,
})
server.on('sessionError', () => {})
server.on('stream', (stream, headers) => {
  stream.on('error', () => {})
  let body = ''
  stream.on('data', data => {
    body += data.toString('utf8')
    if (body.length > 100000) stream.close(http2.constants.NGHTTP2_CANCEL)
  })
  stream.on('end', () => {
    try {
      const request = JSON.parse(body)
      const texts = request.input.texts
      const marker = texts[0].split(/\s/, 1)[0]
      if (headers[':method'] !== 'POST' || request.model !== 'qwen3.7-text-embedding-flash' ||
          request.parameters.dimension !== 1024 || request.parameters.text_type !== 'document') {
        stream.respond({ ':status': 400 }); stream.end(); return
      }
      counts[marker] = (counts[marker] ?? 0) + 1; save()
      if (marker === '[H2_CONTROL]' || marker === '[H2_REFUSED]') {
        stream.close(http2.constants.NGHTTP2_REFUSED_STREAM); return
      }
      const embeddings = texts.map((_, index) => {
        const embedding = Array(marker === '[H2_BAD_DIM]' ? 2 : 1024).fill(0)
        embedding[0] = 1
        return { index, embedding }
      })
      stream.respond({ ':status': 200, 'content-type': 'application/json' })
      stream.end(JSON.stringify({ embeddings, request_id: `synthetic-h2-${counts[marker]}`,
        usage: { prompt_tokens: texts.length * 100, total_tokens: texts.length * 100 } }))
    } catch {
      stream.close(http2.constants.NGHTTP2_INTERNAL_ERROR)
    }
  })
})
server.listen(0, '127.0.0.1', () => {
  save()
  fs.writeFileSync(readyFile, JSON.stringify({ port: server.address().port }))
})
