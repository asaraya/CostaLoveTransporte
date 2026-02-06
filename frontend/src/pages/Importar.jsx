import { useState } from 'react'
import { api } from '../api'

export default function Importar() {
  const [resConsol, setResConsol] = useState(null)
  const [resTracks, setResTracks] = useState(null)
  const [busyC, setBusyC] = useState(false)
  const [busyT, setBusyT] = useState(false)
  const [errC, setErrC] = useState('')
  const [errT, setErrT] = useState('')
  const [nameC, setNameC] = useState('')
  const [nameT, setNameT] = useState('')

  const subirConsolidado = async (ev) => {
    const f = ev.target.files?.[0]
    if (!f) return
    setNameC(f.name)
    setBusyC(true); setErrC(''); setResConsol(null)
    try {
      const form = new FormData()
      form.append('file', f)
      const { data } = await api.post('/import/consolidado', form, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      setResConsol(data)
    } catch (e) {
      setErrC(e?.response?.data?.message || e.message || 'Error subiendo consolidado')
    } finally { setBusyC(false); ev.target.value = '' }
  }

  const subirTracks = async (ev) => {
    const f = ev.target.files?.[0]
    if (!f) return
    setNameT(f.name)
    setBusyT(true); setErrT(''); setResTracks(null)
    try {
      const form = new FormData()
      form.append('file', f)
      const { data } = await api.post('/import/paquetes', form, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      setResTracks(data)
    } catch (e) {
      setErrT(e?.response?.data?.message || e.message || 'Error subiendo tracks')
    } finally { setBusyT(false); ev.target.value = '' }
  }

  return (
    <div className="page" style={{maxWidth:720}}>
      <h3>Importar datos</h3>

      <section>
        <strong>1) Subir Consolidado (XLSX)</strong>
        <p style={{margin:'6px 0'}}>Define marchamo, ubicación y fecha de llegada (received_at).</p>

        <label className="btn file-btn" htmlFor="fileConsol">Seleccionar archivo</label>
        <input id="fileConsol" className="file-hidden" type="file" accept=".xlsx" onChange={subirConsolidado} disabled={busyC}/>
        <span className="file-name">{nameC || 'Ningún archivo seleccionado'}</span>

        {busyC && <div>Subiendo…</div>}
        {errC && <div style={{color:'#e11d48'}}>Error: {errC}</div>}
        {resConsol && <pre style={{background:'#fff',padding:12,border:'1px solid rgba(22,62,122,.12)',borderRadius:8,overflow:'auto'}}>{JSON.stringify(resConsol,null,2)}</pre>}
      </section>

      <section style={{marginTop:12}}>
        <strong>2) Subir Tracks (CSV)</strong>
        <p style={{margin:'6px 0'}}>Actualiza datos del cliente y estados (entregado / devolución).</p>

        <label className="btn file-btn" htmlFor="fileTracks">Seleccionar archivo</label>
        <input id="fileTracks" className="file-hidden" type="file" accept=".csv,text/csv" onChange={subirTracks} disabled={busyT}/>
        <span className="file-name">{nameT || 'Ningún archivo seleccionado'}</span>

        {busyT && <div>Subiendo…</div>}
        {errT && <div style={{color:'#e11d48'}}>Error: {errT}</div>}
        {resTracks && <pre style={{background:'#fff',padding:12,border:'1px solid rgba(22,62,122,.12)',borderRadius:8,overflow:'auto'}}>{JSON.stringify(resTracks,null,2)}</pre>}
      </section>
    </div>
  )
}
