class AppConfiguration {
  static const environment = String.fromEnvironment(
    'ENVIRONMENT',
    defaultValue: 'local',
  );
  static const googleClientId = String.fromEnvironment('GOOGLE_CLIENT_ID');
  static const applicationVersion = String.fromEnvironment(
    'APP_VERSION',
    defaultValue: '0.1.0',
  );
  static const gitRevision = String.fromEnvironment('GIT_REVISION');
  static const buildTime = String.fromEnvironment('BUILD_TIME');
}
