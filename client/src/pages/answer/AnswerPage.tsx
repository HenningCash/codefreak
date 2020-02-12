import { Card, Icon, Menu } from 'antd'
import { SelectParam } from 'antd/lib/menu'
import React, { useEffect } from 'react'
import { Route, Switch, useRouteMatch } from 'react-router-dom'
import ArchiveDownload from '../../components/ArchiveDownload'
import AsyncPlaceholder from '../../components/AsyncContainer'
import FileImport from '../../components/FileImport'
import IdeIframe from '../../components/IdeIframe'
import useSubPath from '../../hooks/useSubPath'
import {
  Answer,
  useGetAnswerQuery,
  useImportAnswerSourceMutation,
  useUploadAnswerSourceMutation
} from '../../services/codefreak-api'
import { messageService } from '../../services/message'
import NotFoundPage from '../NotFoundPage'

const UploadAnswer: React.FC<{ answer: Pick<Answer, 'id'> }> = ({
  answer: { id }
}) => {
  const [
    uploadSource,
    { loading: uploading, data: uploadSuccess }
  ] = useUploadAnswerSourceMutation()

  const [
    importSource,
    { loading: importing, data: importSucess }
  ] = useImportAnswerSourceMutation()

  const onUpload = (files: File[]) => uploadSource({ variables: { files, id } })

  const onImport = (url: string) => importSource({ variables: { url, id } })

  useEffect(() => {
    if (uploadSuccess || importSucess) {
      messageService.success('Source code submitted successfully')
    }
  }, [uploadSuccess, importSucess])

  return (
    <Card title="Upload Source Code">
      <FileImport
        uploading={uploading}
        onUpload={onUpload}
        onImport={onImport}
        importing={importing}
      />
    </Card>
  )
}

const AnswerPage: React.FC<{ answerId: string }> = props => {
  const { path } = useRouteMatch()
  const subPath = useSubPath()

  const result = useGetAnswerQuery({
    variables: { id: props.answerId }
  })

  if (result.data === undefined) {
    return <AsyncPlaceholder result={result} />
  }

  const { answer } = result.data

  const onClickMenu = ({ key }: SelectParam) => {
    subPath.set(key === '/' ? '' : key)
  }

  return (
    <>
      <Menu
        className="content-submenu"
        onSelect={onClickMenu}
        selectedKeys={[subPath.get() || '/']}
        mode="horizontal"
      >
        <Menu.Item key="/">
          <Icon type="file" />
          Current Answer
        </Menu.Item>
        <Menu.Item key="/edit">
          <Icon type="cloud" />
          Online IDE
        </Menu.Item>
        <Menu.Item key="/upload">
          <Icon type="upload" />
          Upload/Import Files
        </Menu.Item>
      </Menu>
      <div className="main-content">
        <Switch>
          <Route exact path={path}>
            <Card
              title="Your current uploaded files"
              extra={
                <ArchiveDownload url={answer.sourceUrl}>
                  Download source code
                </ArchiveDownload>
              }
            >
              WIP
            </Card>
          </Route>
          <Route path={path + '/edit'}>
            <IdeIframe type="answer" id={answer.id} />
          </Route>
          <Route path={path + '/upload'}>
            <UploadAnswer answer={answer} />
          </Route>
          <Route component={NotFoundPage} />
        </Switch>
      </div>
    </>
  )
}

export default AnswerPage
