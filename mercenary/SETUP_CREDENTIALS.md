# How to Configure MongoDB Credentials Safely

Your application is now configured to check for an environment variable named `MONGODB_URI`. If it finds one, it uses it. If not, it defaults to `localhost` (safe for dev).

Here is how to provide your **production** or **cloud** password without editing the code.

## Option 1: IntelliJ IDEA (Recommended for Development)
This keeps your secret only in your local IDE settings, which are never committed to Git.

1.  Click the **Run Configuration** dropdown (next to the green Play button) and select **Edit Configurations...**
2.  Select your Spring Boot application (e.g., `MercenaryApplication`).
3.  Look for the **Environment variables** field.
4.  Click the version icon (page with a $) or just type directly:
    ```properties
    MONGODB_URI=mongodb+srv://<username>:<new_password>@<cluster>.mongodb.net/mercenary
    ```
5.  Click **OK** and run the app.

## Option 2: PowerShell (Terminal)
If you run the app from the command line:

```powershell
# Set the variable for the current session
$env:MONGODB_URI = "mongodb+srv://<username>:<new_password>@<cluster>.mongodb.net/mercenary"

# Run the app
./gradlew bootRun
```

## Option 3: Windows System Variables (Permanent)
To set it globally for your user (so you don't have to set it every time):

1.  Press `Win + R`, type `rundll32 sysdm.cpl,EditEnvironmentVariables`, and hit Enter.
2.  Under **User variables**, click **New...**
3.  **Variable name**: `MONGODB_URI`
4.  **Variable value**: `mongodb+srv://...`
5.  Click **OK**. You may need to restart your terminal or IDE for this to take effect.
