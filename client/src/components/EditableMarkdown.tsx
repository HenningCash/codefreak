import { Button, Empty, Icon } from 'antd'
import React, { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import ReactMde from 'react-mde'
import { GenerateMarkdownPreview } from 'react-mde/lib/definitions/types'
import 'react-mde/lib/styles/css/react-mde-all.css'
import './EditableMarkdown.less'

const generateMarkdownProview: GenerateMarkdownPreview = markdown =>
  Promise.resolve(<ReactMarkdown source={markdown} />)

const EditableMarkdown: React.FC<{
  content?: string | null
  editable: boolean
  onSave: (newContent: string | undefined) => Promise<any>
}> = ({ content, editable, onSave }) => {
  const [editing, setEditing] = useState(false)
  const [newContent, setNewContent] = useState<string>()
  const [selectedTab, setSelectedTab] = React.useState<'write' | 'preview'>(
    'write'
  )
  const [saving, setSaving] = useState(false)
  const edit = () => {
    setNewContent(content || undefined)
    setEditing(true)
  }
  const save = () => {
    setSaving(true)
    onSave(newContent)
      .then(() => setEditing(false))
      .finally(() => setSaving(false))
  }
  const cancel = () => setEditing(false)
  if (editing) {
    return (
      <>
        <ReactMde
          value={newContent}
          onChange={setNewContent}
          selectedTab={selectedTab}
          onTabChange={setSelectedTab}
          generateMarkdownPreview={generateMarkdownProview}
        />
        <div style={{ marginTop: 8 }}>
          <Button style={{ marginRight: 8 }} onClick={cancel}>
            Cancel
          </Button>
          <Button type="primary" loading={saving} onClick={save}>
            Save
          </Button>
        </div>
      </>
    )
  } else {
    return (
      <div
        onClick={editable ? edit : undefined}
        className={editable ? 'markdown-wrapper editable' : 'markdown-wrapper'}
      >
        <Icon type="edit" className="edit-icon" />
        {content || !editable ? (
          <ReactMarkdown source={content || ''} />
        ) : (
          <Empty description={null} />
        )}
      </div>
    )
  }
}

export default EditableMarkdown
