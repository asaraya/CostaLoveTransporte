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

test('Heredia y CostaLove conservan la misma lógica, pruebas y SQL', { skip: !paired && 'Repositorio compañero ausente en este entorno' }, () => {
  const directories = ['backend/src/main/java', 'backend/src/test', 'frontend/src', 'frontend/tests', 'database']
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

test('el instalador no ejecuta operaciones sobre datos existentes', () => {
  const sql = read(resolve(root, 'database/actualizar_fechas.sql'))
  let delimiter = ';'
  let statement = ''
  let procedures = 0
  for (const line of sql.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('--')) continue
    if (trimmed.startsWith('DELIMITER ')) { delimiter = trimmed.slice(10); continue }
    statement += line + '\n'
    if (!trimmed.endsWith(delimiter)) continue
    const value = statement.trim()
    if (/^CREATE PROCEDURE\b/i.test(value)) procedures++
    else assert.match(value, /^(?:CREATE TABLE IF NOT EXISTS\b|CREATE TRIGGER\b|DROP TRIGGER IF EXISTS\b|CREATE OR REPLACE SQL SECURITY INVOKER VIEW\b|DROP PROCEDURE IF EXISTS\b|SET NAMES utf8mb4;|SET @fechas_sql = IF\(|PREPARE fechas_stmt FROM @fechas_sql;|EXECUTE fechas_stmt;|DEALLOCATE PREPARE fechas_stmt;)/i, 'Instrucción de instalación inesperada: ' + value.slice(0, 100))
    statement = ''
  }
  assert.equal(statement.trim(), '')
  assert.equal(procedures, 121)
  assert.doesNotMatch(sql, /CREATE (?:PROCEDURE|TRIGGER) IF NOT EXISTS/i)
  assert.equal((sql.match(/^DELIMITER \$\$$/gm) || []).length, 122)
  assert.equal((sql.match(/^DROP PROCEDURE IF EXISTS /gm) || []).length, 121)
  const dynamicSql = [...sql.matchAll(/'([^'\r\n]*)'/g)].map(match => match[1]).filter(value => value.startsWith('ALTER TABLE'))
  assert.deepEqual(dynamicSql, [
    'ALTER TABLE paquetes ADD COLUMN registered_at DATETIME NULL',
    'ALTER TABLE paquete_audit_log ADD COLUMN effective_at DATETIME NULL',
  ])
  assert.equal((sql.match(/SET @fechas_sql =/g) || []).length, 2)
})
