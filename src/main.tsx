import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { DockApp } from './dock/DockApp'
import './styles/global.css'
import './dock/dock.css'

const isDock = new URLSearchParams(window.location.search).get('view') === 'dock'
if (isDock) document.documentElement.classList.add('dock-root')

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {isDock ? <DockApp /> : <App />}
  </StrictMode>,
)
