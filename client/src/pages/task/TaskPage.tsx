import { PageHeaderWrapper } from '@ant-design/pro-layout'
import { Button, Icon, Tooltip } from 'antd'
import { Switch as AntSwitch } from 'antd'
import React, { createContext } from 'react'
import { Redirect, Route, Switch, useRouteMatch } from 'react-router-dom'
import { useHistory } from 'react-router-dom'
import AsyncPlaceholder from '../../components/AsyncContainer'
import { createBreadcrumb } from '../../components/DefaultLayout'
import EditableTitle from '../../components/EditableTitle'
import EvaluationIndicator from '../../components/EvaluationIndicator'
import IdeIframe from '../../components/IdeIframe'
import SetTitle from '../../components/SetTitle'
import StartEvaluationButton from '../../components/StartEvaluationButton'
import useHasAuthority from '../../hooks/useHasAuthority'
import useIdParam from '../../hooks/useIdParam'
import { useQueryParam } from '../../hooks/useQuery'
import useSubPath from '../../hooks/useSubPath'
import {
  PublicUserFieldsFragment,
  TaskInput,
  useCreateAnswerMutation,
  useDeleteAnswerMutation,
  useGetTaskQuery,
  useUpdateTaskMutation
} from '../../services/codefreak-api'
import { createRoutes } from '../../services/custom-breadcrump'
import { getEntityPath } from '../../services/entity-path'
import { messageService } from '../../services/message'
import { unshorten } from '../../services/short-id'
import { displayName } from '../../services/user'
import { makeUpdater } from '../../services/util'
import AnswerPage from '../answer/AnswerPage'
import EvaluationPage from '../evaluation/EvaluationOverviewPage'
import NotFoundPage from '../NotFoundPage'
import TaskDetailsPage from './TaskDetailsPage'

export const DifferentUserContext = createContext<
  PublicUserFieldsFragment | undefined
>(undefined)

const tab = (title: string, icon: string) => (
  <>
    <Icon type={icon} /> {title}
  </>
)

const TaskPage: React.FC = () => {
  const { path, url } = useRouteMatch()
  const subPath = useSubPath()
  const isTeacher = useHasAuthority('ROLE_TEACHER')
  const userId = useQueryParam('user')
  const history = useHistory()

  const result = useGetTaskQuery({
    variables: {
      id: useIdParam(),
      answerUserId: isTeacher && userId ? unshorten(userId) : undefined
    }
  })

  const [createAnswer, { loading: creatingAnswer }] = useCreateAnswerMutation()
  const [deleteAnswer, { loading: deletingAnswer }] = useDeleteAnswerMutation()

  const [updateMutation] = useUpdateTaskMutation({
    onCompleted: () => {
      result.refetch()
      messageService.success('Task updated')
    }
  })

  if (result.data === undefined) {
    return <AsyncPlaceholder result={result} />
  }
  const { task } = result.data
  const { answer } = task
  const differentUser =
    isTeacher && answer && userId ? answer.submission.user : undefined
  const editable = task.editable && !differentUser

  const taskInput: TaskInput = {
    id: task.id,
    title: task.title
  }

  const updater = makeUpdater(taskInput, input =>
    updateMutation({ variables: { input } })
  )

  const setTestingMode = (enabled: boolean) => {
    if (enabled) {
      onCreateAnswer()
    } else {
      deleteAnswer({ variables: { id: answer!.id } }).then(() => {
        subPath.set('')
        result.refetch()
      })
    }
  }

  const testingModeSwitch =
    editable && !differentUser
      ? [
          {
            key: 'testing-mode',
            tab: (
              <span style={{ cursor: 'default', color: 'rgba(0, 0, 0, 0.65)' }}>
                Testing Mode{' '}
                <Tooltip
                  placement="right"
                  title="Enable this for testing the automatic evaluation. This will create an answer like students would do. Disabling deletes the answer."
                >
                  <AntSwitch
                    onChange={setTestingMode}
                    style={{ marginLeft: 8 }}
                    checked={answer !== null}
                    loading={creatingAnswer || deletingAnswer}
                  />
                </Tooltip>
              </span>
            )
          }
        ]
      : []

  const tabs = [
    { key: '', tab: tab('Task', 'file-text') },
    ...testingModeSwitch,
    { key: '/answer', tab: tab('Answer', 'solution'), disabled: !answer },
    { key: '/ide', tab: tab('Online IDE', 'cloud'), disabled: !answer },
    {
      key: '/evaluation',
      disabled: !answer,
      tab: (
        <>
          {tab('Evaluation', 'dashboard')}
          {answer ? (
            <EvaluationIndicator
              style={{ marginLeft: 8 }}
              answerId={answer.id}
            />
          ) : null}
        </>
      )
    }
  ]

  const onCreateAnswer = async () => {
    const createAnswerResult = await createAnswer({
      variables: { taskId: task.id }
    })
    if (createAnswerResult.data) {
      subPath.set('/answer')
      result.refetch()
    }
  }

  const assignment = task.assignment

  let buttons
  if (differentUser && assignment) {
    // Show "back to submissions" button for teachers
    const onClick = () =>
      history.push(getEntityPath(assignment) + '/submissions')
    buttons = (
      <Button icon="arrow-left" size="large" onClick={onClick}>
        Back to submissions
      </Button>
    )
  } else if (answer) {
    // regular buttons to work on task for students
    buttons = (
      <StartEvaluationButton answerId={answer.id} type="primary" size="large" />
    )
  } else if (!editable && !differentUser) {
    // start working on task by default
    buttons = (
      <Button
        icon="rocket"
        size="large"
        type="primary"
        onClick={onCreateAnswer}
        loading={creatingAnswer}
      >
        Start working on this task!
      </Button>
    )
  }

  const title = differentUser
    ? `${task.title} – ${displayName(differentUser)}`
    : task.title

  const onTabChange = (activeKey: string) => {
    if (activeKey !== 'testing-mode') {
      subPath.set(activeKey, userId ? { user: userId } : undefined)
    }
  }

  return (
    <DifferentUserContext.Provider value={differentUser}>
      <SetTitle>{task.title}</SetTitle>
      <PageHeaderWrapper
        title={
          <EditableTitle
            editable={editable}
            title={title}
            onChange={updater('title')}
          />
        }
        breadcrumb={createBreadcrumb(createRoutes.forTask(task))}
        tabList={tabs}
        tabActiveKey={subPath.get()}
        onTabChange={onTabChange}
        extra={buttons}
      />
      <Switch>
        <Route exact path={path}>
          <Redirect to={`${url}/details`} />
        </Route>
        <Route path={`${path}/details`}>
          <TaskDetailsPage editable={editable} />
        </Route>
        <Route path={`${path}/answer`}>
          {answer ? <AnswerPage answerId={answer.id} /> : <NotFoundPage />}
        </Route>
        <Route path={`${path}/evaluation`}>
          {answer ? <EvaluationPage answerId={answer.id} /> : <NotFoundPage />}
        </Route>
        <Route path={`${path}/ide`}>
          {answer ? (
            <div className="no-padding">
              <IdeIframe type="answer" id={answer.id} />
            </div>
          ) : (
            <NotFoundPage />
          )}
        </Route>
        <Route component={NotFoundPage} />
      </Switch>
    </DifferentUserContext.Provider>
  )
}

export default TaskPage
