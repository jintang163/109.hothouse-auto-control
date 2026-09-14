import React, { useEffect, useState } from 'react'
import { Layout, Menu, Select, Typography } from 'antd'
import {
  DashboardOutlined, LineChartOutlined, ControlOutlined,
  AlertOutlined, AuditOutlined, ToolOutlined, BookOutlined,
  UnorderedListOutlined, ExperimentOutlined, BugOutlined
} from '@ant-design/icons'
import { BrowserRouter, Routes, Route, useNavigate, useLocation } from 'react-router-dom'
import Dashboard from './pages/Dashboard.jsx'
import History from './pages/History.jsx'
import StrategyPage from './pages/StrategyPage.jsx'
import Alarms from './pages/Alarms.jsx'
import Trace from './pages/Trace.jsx'
import Maintenance from './pages/Maintenance.jsx'
import Prescription from './pages/Prescription.jsx'
import FarmTasks from './pages/FarmTasks.jsx'
import FarmLog from './pages/FarmLog.jsx'
import PestLib from './pages/PestLib.jsx'
import { api } from './api'

const { Header, Sider, Content } = Layout

function Shell() {
  const [greenhouses, setGreenhouses] = useState([])
  const [ghId, setGhId] = useState(null)
  const navigate = useNavigate()
  const location = useLocation()

  useEffect(() => {
    api.listGreenhouses().then(list => {
      setGreenhouses(list)
      if (list.length) setGhId(list[0].id)
    })
  }, [])

  const selectedKey = ['/', '/history', '/strategy', '/alarms', '/trace', '/maintenance',
    '/prescription', '/farm-tasks', '/farm-log', '/pest']
    .find(p => p === location.pathname) || '/'

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', paddingInline: 20, gap: 24 }}>
        <Typography.Title level={4} style={{ color: '#fff', margin: 0, whiteSpace: 'nowrap' }}>
          🌱 智慧温室环控平台
        </Typography.Title>
        <Menu
          theme="dark" mode="horizontal" selectedKeys={[selectedKey]}
          style={{ flex: 'auto', minWidth: 0 }}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: '/', icon: <DashboardOutlined />, label: '实时监控' },
            { key: '/history', icon: <LineChartOutlined />, label: '历史曲线' },
            { key: '/strategy', icon: <ControlOutlined />, label: '环控策略' },
            { key: '/prescription', icon: <BookOutlined />, label: '农事处方' },
            { key: '/farm-tasks', icon: <UnorderedListOutlined />, label: '农事任务' },
            { key: '/farm-log', icon: <ExperimentOutlined />, label: '农事日志' },
            { key: '/pest', icon: <BugOutlined />, label: '病虫害库' },
            { key: '/alarms', icon: <AlertOutlined />, label: '告警中心' },
            { key: '/maintenance', icon: <ToolOutlined />, label: '设备运维' },
            { key: '/trace', icon: <AuditOutlined />, label: '操作追溯' }
          ]}
        />
        <Select
          value={ghId}
          onChange={setGhId}
          style={{ width: 200 }}
          options={greenhouses.map(g => ({
            value: g.id,
            label: `${g.name}（${g.crop || '-'}）`
          }))}
        />
      </Header>
      <Content>
        {ghId == null ? null : (
          <Routes>
            <Route path="/" element={<Dashboard greenhouseId={ghId} />} />
            <Route path="/history" element={<History greenhouseId={ghId} />} />
            <Route path="/strategy" element={<StrategyPage greenhouseId={ghId} />} />
            <Route path="/prescription" element={<Prescription greenhouseId={ghId} />} />
            <Route path="/farm-tasks" element={<FarmTasks greenhouseId={ghId} />} />
            <Route path="/farm-log" element={<FarmLog greenhouseId={ghId} />} />
            <Route path="/pest" element={<PestLib greenhouseId={ghId} />} />
            <Route path="/alarms" element={<Alarms />} />
            <Route path="/maintenance" element={<Maintenance />} />
            <Route path="/trace" element={<Trace greenhouseId={ghId} />} />
          </Routes>
        )}
      </Content>
    </Layout>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Shell />
    </BrowserRouter>
  )
}
