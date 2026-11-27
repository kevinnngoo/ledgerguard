import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth'
import { Layout } from './components/Layout'
import { AccountDetail } from './pages/AccountDetail'
import { Accounts } from './pages/Accounts'
import { Audit } from './pages/Audit'
import { Ledger } from './pages/Ledger'
import { Login } from './pages/Login'
import { Overview } from './pages/Overview'
import { SimulationLab } from './pages/SimulationLab'
import { TransactionDetail } from './pages/TransactionDetail'
import { Transactions } from './pages/Transactions'

function Routed() {
  const { me } = useAuth()
  if (!me) return <Login />
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Overview />} />
        <Route path="accounts" element={<Accounts />} />
        <Route path="accounts/:id" element={<AccountDetail />} />
        <Route path="transactions" element={<Transactions />} />
        <Route path="transactions/:id" element={<TransactionDetail />} />
        <Route path="ledger" element={<Ledger />} />
        <Route path="simulation" element={<SimulationLab />} />
        <Route path="audit" element={<Audit />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routed />
      </BrowserRouter>
    </AuthProvider>
  )
}
