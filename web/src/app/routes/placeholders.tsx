interface RoutePlaceholderProps {
  title: string
  description: string
}

function RoutePlaceholder({ title, description }: RoutePlaceholderProps) {
  return (
    <section className="route-placeholder">
      <h2>{title}</h2>
      <p>{description}</p>
    </section>
  )
}

export function NoConversationPlaceholder() {
  return (
    <RoutePlaceholder
      title="No conversation selected"
      description="Conversation routing and the chat view arrive in M1."
    />
  )
}

export function ChatPlaceholder() {
  return (
    <RoutePlaceholder
      title="Chat placeholder"
      description="The chat view for this conversation arrives in M1."
    />
  )
}

export function SettingsAppearancePlaceholder() {
  return (
    <RoutePlaceholder
      title="Appearance settings placeholder"
      description="Theme, density, and font size controls arrive in M0-03.2."
    />
  )
}

export {
  ProviderDetailRoute as SettingsProviderDetailPlaceholder,
  ProvidersSettingsRoute as SettingsProvidersPlaceholder,
} from '../../features/providers'
export { ModelsSettingsRoute as SettingsModelsPlaceholder } from '../../features/settings'

export function SettingsProviderNewPlaceholder() {
  return (
    <RoutePlaceholder
      title="New provider placeholder"
      description="Provider onboarding arrives in M1."
    />
  )
}

export function SettingsDataPlaceholder() {
  return (
    <RoutePlaceholder
      title="Data settings placeholder"
      description="Storage usage and export/import controls arrive in M1."
    />
  )
}

export function SettingsBackupPlaceholder() {
  return (
    <RoutePlaceholder
      title="Backup settings placeholder"
      description="Backup configuration arrives in M5."
    />
  )
}

export function SettingsAboutPlaceholder() {
  return (
    <RoutePlaceholder
      title="About settings placeholder"
      description="Version, licenses, and privacy information arrive in M1."
    />
  )
}

export function NotFoundPlaceholder() {
  return (
    <section className="route-placeholder" role="alert">
      <h2>Page not found</h2>
      <p>The requested route does not exist.</p>
    </section>
  )
}
