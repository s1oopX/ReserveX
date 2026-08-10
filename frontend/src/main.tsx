import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* BrowserRouter 而非 HashRouter:08 §五 Caddy 已配 try_files {path} /index.html,
        深链刷新不会 404。改成 HashRouter 会让 URL 里多个 #,分享链接变丑且无必要。 */}
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
