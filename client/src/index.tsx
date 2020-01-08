import { ApolloProvider } from '@apollo/react-hooks'
import { InMemoryCache } from 'apollo-cache-inmemory'
import ApolloClient from 'apollo-client'
import { ApolloLink } from 'apollo-link'
import { split } from 'apollo-link'
import { onError } from 'apollo-link-error'
import { WebSocketLink } from 'apollo-link-ws'
import { createUploadLink } from 'apollo-upload-client'
import { getMainDefinition } from 'apollo-utilities'
import React from 'react'
import ReactDOM from 'react-dom'
import App from './App'
import './index.css'
import { extractErrorMessage } from './services/codefreak-api'
import { messageService } from './services/message'
import * as serviceWorker from './serviceWorker'

const httpLink = createUploadLink({ uri: '/graphql', credentials: 'include' })

const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'

const wsLink = new WebSocketLink({
  uri: `${wsProtocol}//${window.location.host}/subscriptions`,
  options: {
    reconnect: true,
    connectionParams: () => ({
      credentials: 'include'
    })
  }
})

// Based on https://github.com/apollographql/apollo-link/issues/197#issuecomment-363387875
// We need to re-connect the websocket so that the new session cookie is used for the handshake
const resetWebsocket = () => {
  const websocket = (wsLink as any).subscriptionClient
  websocket.close(true, true)
  websocket.connect()
}

const apolloClient = new ApolloClient({
  link: ApolloLink.from([
    onError(error => {
      if (!error.operation.getContext().disableGlobalErrorHandling) {
        messageService.error(extractErrorMessage(error))
      }
    }),
    split(
      ({ query }) => {
        const definition = getMainDefinition(query)
        return (
          definition.kind === 'OperationDefinition' &&
          definition.operation === 'subscription'
        )
      },
      wsLink,
      httpLink
    )
  ]),
  cache: new InMemoryCache()
})

ReactDOM.render(
  <ApolloProvider client={apolloClient}>
    <App onUserChanged={resetWebsocket} />
  </ApolloProvider>,
  document.getElementById('root')
)

// If you want your app to work offline and load faster, you can change
// unregister() to register() below. Note this comes with some pitfalls.
// Learn more about service workers: https://bit.ly/CRA-PWA
serviceWorker.unregister()
