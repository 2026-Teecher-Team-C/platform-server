import { Navigate, createBrowserRouter } from 'react-router-dom'
import { AppLayout } from '../components/layout/AppLayout'
import { AgentsPage } from '../pages/AgentsPage'
import { AuditLogsPage } from '../pages/AuditLogsPage'
import { BlacklistPage } from '../pages/BlacklistPage'
import { DashboardPage } from '../pages/DashboardPage'
import { EventDetailPage } from '../pages/EventDetailPage'
import { EventsPage } from '../pages/EventsPage'
import { LoginPage } from '../pages/LoginPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { PoliciesPage } from '../pages/PoliciesPage'
import { QuarantinePage } from '../pages/QuarantinePage'
import { RulesetsPage } from '../pages/RulesetsPage'
import { WhitelistPage } from '../pages/WhitelistPage'

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: <AppLayout />,
    children: [
      {
        index: true,
        element: <Navigate to="/dashboard" replace />,
      },
      {
        path: 'dashboard',
        element: <DashboardPage />,
      },
      {
        path: 'events',
        element: <EventsPage />,
      },
      {
        path: 'events/:eventId',
        element: <EventDetailPage />,
      },
      {
        path: 'quarantine',
        element: <QuarantinePage />,
      },
      {
        path: 'agents',
        element: <AgentsPage />,
      },
      {
        path: 'whitelist',
        element: <WhitelistPage />,
      },
      {
        path: 'blacklist',
        element: <BlacklistPage />,
      },
      {
        path: 'policies',
        element: <PoliciesPage />,
      },
      {
        path: 'rulesets',
        element: <RulesetsPage />,
      },
      {
        path: 'audit-logs',
        element: <AuditLogsPage />,
      },
      {
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
])
