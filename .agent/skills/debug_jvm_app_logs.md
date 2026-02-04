---
name: Debug JVM App with Logs
description: A workflow for launching the JVM application in the background and actively monitoring its logs to identify runtime errors, specifically for debugging issues like persistence or UI crashes.
---

# Debug JVM App with Logs

This workflow describes how to run the Compose/JVM application and "tail" the logs to capture runtime errors that do not appear during the build phase.

## 1. Launch the Application

Use `run_command` to start the application in the background. It is crucial to set `WaitMsBeforeAsync` to a low value (e.g., 500-1000ms) so the tool returns the `CommandId` immediately, allowing you to start monitoring.

```yaml
Tool: run_command
Args:
  CommandLine: ./gradlew :apps:composeApp:run
  Cwd: <Project Root>
  SafeToAutoRun: true
  WaitMsBeforeAsync: 1000
```

## 2. Monitor Logs (Tailing)

Immediately after launching, use `command_status` in a loop (or repeatedly) to read the output.
- **WaitDurationSeconds**: Set to a reasonable blocking time (e.g., 5-10 seconds) to gather a batch of logs.
- **OutputCharacterCount**: Set high enough (e.g., 5000) to capture stack traces.

```yaml
Tool: command_status
Args:
  CommandId: <ID from step 1>
  WaitDurationSeconds: 10
  OutputCharacterCount: 5000
```

## 3. Analyze and Iterate

Read the logs returned by `command_status`.
- If you see exceptions (e.g., `SerializationException`, `NullPointerException`), analyze the stack trace.
- If the app is waiting for input or hanging, the logs will indicate the last activity.
- To stop the app, use `send_command_input` with `Terminate: true`.

## Example: Debugging Preset Saving

If debugging a "Save" failure:
1. Launch app.
2. Monitor startup logs to ensure the app is ready.
3. If you cannot interact with the UI to trigger the save:
    - Look for logs indicating the app state.
    - If the error happens on startup (e.g., loading bad presets), it will appear here.
    - **Note**: If manual interaction is required (clicking a button), you must ask the **USER** to perform the action while you are monitoring the logs, or write a test case to reproduce it programmatically.

## Common Issues & Fixes

- **Serialization Errors**: "Serializer for subclass not found".
    - **Fix**: Ensure sealed classes behave polymorphically. Add `@SerialName` to subclasses. Check `Json` configuration (`classDiscriminator`, `serializersModule`).
- **Missing Class Def**: "NoClassDefFoundError".
    - **Fix**: Check dependencies and imports.
