import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './lib/auth.tsx'
import SignIn from './pages/SignIn.tsx'
import Home from './pages/Home.tsx'
import Production from './pages/Production.tsx'

export default function App() {
  const { user } = useAuth()

  if (!user) {
    return <SignIn />
  }

  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/production/:id/*" element={<Production />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
