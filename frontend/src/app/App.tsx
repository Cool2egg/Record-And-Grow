import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './AppShell'
import { ChatPage } from '../features/chat/ChatPage'
import { TimelinePage } from '../features/timeline/TimelinePage'
import { MemoryPage } from '../features/memory/MemoryPage'
import { DocumentsPage } from '../features/documents/DocumentsPage'
import { GraphPage } from '../features/graph/GraphPage'
import { StatusPage } from '../features/status/StatusPage'

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppShell />}>
          <Route index element={<ChatPage />} />
          <Route path="timeline" element={<TimelinePage />} />
          <Route path="memory" element={<MemoryPage />} />
          <Route path="documents" element={<DocumentsPage />} />
          <Route path="graph" element={<GraphPage />} />
          <Route path="status" element={<StatusPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
