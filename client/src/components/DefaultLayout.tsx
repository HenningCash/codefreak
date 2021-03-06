import ProLayout from '@ant-design/pro-layout'
import { BasicLayoutProps } from '@ant-design/pro-layout/lib/BasicLayout'
import { MenuDataItem } from '@ant-design/pro-layout/lib/typings'
import { Alert } from 'antd'
import { Route } from 'antd/lib/breadcrumb/Breadcrumb'
import React from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Authority } from '../hooks/useHasAuthority'
import useSubscribeToGlobalEvents from '../hooks/useSubscribeToGlobalEvents'
import useSystemConfig from '../hooks/useSystemConfig'
import { routerConfig } from '../router.config'
import { createOptionState } from '../services/options'
import AppFooter from './AppFooter'
import Authorized from './Authorized'
import './DefaultLayout.less'
import Logo from './Logo'
import RightHeader from './RightHeader'

export const appName = 'Code FREAK'

export const breadcrumbItemRender = (route: Route, _: any, routes: Route[]) => {
  const last = routes.indexOf(route) === routes.length - 1
  return last ? (
    <span>{route.breadcrumbName}</span>
  ) : (
    <Link to={route.path}>{route.breadcrumbName}</Link>
  )
}

export const createBreadcrumb = (routes: Route[]) => ({
  routes,
  itemRender: breadcrumbItemRender
})

interface DefaultLayoutProps extends Partial<BasicLayoutProps> {
  logout?: () => void
}

const useSidebarCollapsedState = createOptionState('sidebar-collapsed')
const DefaultLayout: React.FC<DefaultLayoutProps> = ({
  logout,
  children,
  ...additionProps
}) => {
  useLocation() // somehow this is needed for 'active navigation item' to work correctly 🤔

  useSubscribeToGlobalEvents()
  const [sidebarCollapsed, setSidebarCollapsed] = useSidebarCollapsedState(
    false
  )

  const { data: motd } = useSystemConfig('motd')

  const renderRightHeader = () => <RightHeader logout={logout} />
  const renderFooter = () => <AppFooter />
  const renderHeader = (_: any, defaultDom: React.ReactNode) => (
    <>
      {defaultDom}
      {motd ? <Alert banner message={motd} /> : null}
    </>
  )

  return (
    <ProLayout
      menuItemRender={menuItemRender}
      route={routerConfig}
      title={appName}
      logo={<Logo />}
      breakpoint={false}
      disableContentMargin={false}
      collapsed={sidebarCollapsed}
      onCollapse={setSidebarCollapsed}
      itemRender={breadcrumbItemRender}
      rightContentRender={renderRightHeader}
      footerRender={renderFooter}
      headerRender={renderHeader}
      {...additionProps}
    >
      {children}
    </ProLayout>
  )
}

const menuItemRender = (
  menuItemProps: MenuDataItem,
  defaultDom: React.ReactNode
) => {
  if (menuItemProps.isUrl || menuItemProps.children || !menuItemProps.path) {
    return defaultDom
  }
  return (
    <Authorized authority={menuItemProps.authority as Authority}>
      <Link to={menuItemProps.path}>{defaultDom}</Link>
    </Authorized>
  )
}

export default DefaultLayout
