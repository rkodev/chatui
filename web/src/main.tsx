import { bootstrap } from './app/bootstrap'
import { registerServiceWorker } from './platform/pwa/register'
import { usePwaUpdateStore } from './platform/pwa/updateStore'

bootstrap()

// The registration helper is a no-op outside production builds, so development
// never installs or activates a service worker.
void registerServiceWorker({
  onUpdateReady: (registration) => {
    usePwaUpdateStore.getState().markWaiting(registration)
  },
})
