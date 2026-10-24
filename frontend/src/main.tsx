import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import 'lxgw-wenkai-screen-webfont/lxgwwenkaiscreen.css'
import 'antd/dist/reset.css'
import './styles/base.css'
import './styles/chat.css'
import './styles/pages.css'
import { App } from './app/App'
import { Providers } from './app/providers'

dayjs.locale('zh-cn')

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Providers>
      <App />
    </Providers>
  </StrictMode>,
)
