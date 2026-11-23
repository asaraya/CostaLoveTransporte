import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const candidates = [
  resolve(root, '../HerediaTransporte/TransporteHeredia'),
  resolve(root, '../../CostaLoveTransporte'),
]
const paired = candidates.find(dir => dir !== root && existsSync(resolve(dir, 'backend/pom.xml')))
const identityFiles = new Set(['frontend/src/assets/cargo_logo.png', 'frontend/src/assets/cargo_logo.svg'])

function files(dir) {
  if (!existsSync(dir)) return []
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = resolve(dir, entry.name)
    return entry.isDirectory() ? files(path) : [path]
  })
}
function read(path) { return readFileSync(path, 'utf8').replaceAll('\r\n', '\n') }

test('Heredia y CostaLove conservan la misma lógica y pruebas', { skip: !paired && 'Repositorio compañero ausente en este entorno' }, () => {
  const directories = ['backend/src/main/java', 'backend/src/test', 'frontend/src', 'frontend/tests']
  for (const directory of directories) {
    const a = files(resolve(root, directory)).map(path => relative(root, path).replaceAll('\\', '/')).filter(path => !identityFiles.has(path)).sort()
    const b = files(resolve(paired, directory)).map(path => relative(paired, path).replaceAll('\\', '/')).filter(path => !identityFiles.has(path)).sort()
    assert.deepEqual(a, b, 'Archivos distintos en ' + directory)
    for (const path of a) assert.equal(read(resolve(root, path)), read(resolve(paired, path)), 'Actualizar ambas sucursales: ' + path)
  }
  for (const path of ['frontend/Dockerfile', 'frontend/nginx.conf.template', 'frontend/railway.json',
    'frontend/index.html', 'frontend/vite.config.js', 'backend/Dockerfile', 'backend/railway.json']) {
    assert.equal(read(resolve(root, path)), read(resolve(paired, path)), 'Configuración funcional distinta: ' + path)
  }
  const packageA = JSON.parse(read(resolve(root, 'frontend/package.json')))
  const packageB = JSON.parse(read(resolve(paired, 'frontend/package.json')))
  delete packageA.name
  delete packageB.name
  assert.deepEqual(packageA, packageB, 'Dependencias o scripts distintos')
})
