# Deploying to Google Cloud

This guide puts Board Game Master on **Cloud Run** with a **Cloud SQL for PostgreSQL** database,
secrets in **Secret Manager**, in the **Jakarta region** (`asia-southeast2`). Run the commands in
PowerShell from the project folder.

What you end up with:

- A public `https://….run.app` site where anyone can browse games, learn and play.
- The admin area (`/admin`) and every content-changing API locked behind a username and password.
- Rule questions and voice turned on with your OpenAI key, with built-in caps on AI requests.

## 0. Before you start

1. Run the tests locally and make sure they pass: `.\gradlew.bat check bootJar`.
2. Install the [Google Cloud CLI](https://cloud.google.com/sdk/docs/install), then sign in:

```powershell
gcloud auth login
$PROJECT = "your-project-id"        # your existing project
$REGION  = "asia-southeast2"        # Jakarta
gcloud config set project $PROJECT
```

3. Turn on the services this guide uses (one time):

```powershell
gcloud services enable run.googleapis.com sqladmin.googleapis.com artifactregistry.googleapis.com `
  cloudbuild.googleapis.com secretmanager.googleapis.com
```

## 1. Create the database

The smallest shared-core machine (`db-f1-micro`) is only available in the Enterprise edition, so the
edition is set explicitly. Cloud SQL supports the pgvector extension the app needs.

```powershell
gcloud sql instances create bgm-db --database-version=POSTGRES_17 --edition=ENTERPRISE `
  --tier=db-f1-micro --region=$REGION --storage-size=10
gcloud sql databases create board_game_master --instance=bgm-db
```

Create the app's database user with a random password:

```powershell
$DB_PASSWORD = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
gcloud sql users create bgm_app --instance=bgm-db --password=$DB_PASSWORD
```

The first app start runs the Flyway migrations, which enable pgvector and seed Ticket to Ride.

## 2. Store the secrets

Secrets are written to a temporary file first, because piping text in PowerShell adds a line break
that would become part of the secret.

```powershell
function New-Secret($name, $value) {
  $file = Join-Path $env:TEMP "$name.txt"
  [IO.File]::WriteAllText($file, $value)
  gcloud secrets create $name --data-file=$file
  Remove-Item $file
}

$ADMIN_PASSWORD = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
$OPENAI_KEY = Read-Host "Paste your OpenAI API key"

New-Secret "bgm-db-password" $DB_PASSWORD
New-Secret "bgm-admin-password" $ADMIN_PASSWORD
New-Secret "bgm-openai-key" $OPENAI_KEY
```

Use a dedicated OpenAI **project key** for this app, and set a monthly **budget limit** on that
project in the OpenAI dashboard. That limit is the hard stop on spending; the app's own caps
(below) only slow things down.

## 3. Create the app's service account

The app runs as its own account that may only connect to Cloud SQL and read its three secrets.

```powershell
gcloud iam service-accounts create bgm-run --display-name="Board Game Master (Cloud Run)"
$SA = "bgm-run@$PROJECT.iam.gserviceaccount.com"
gcloud projects add-iam-policy-binding $PROJECT --member="serviceAccount:$SA" --role="roles/cloudsql.client"
foreach ($s in "bgm-db-password", "bgm-admin-password", "bgm-openai-key") {
  gcloud secrets add-iam-policy-binding $s --member="serviceAccount:$SA" --role="roles/secretmanager.secretAccessor"
}
```

## 4. Deploy

`--source .` uploads the project, builds the `Dockerfile` with Cloud Build and deploys the image.
The first time, gcloud asks to create an Artifact Registry repository: answer **Y**.

```powershell
$INSTANCE = "${PROJECT}:${REGION}:bgm-db"
$DB_URL = "jdbc:postgresql:///board_game_master?cloudSqlInstance=$INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory"

gcloud run deploy board-game-master `
  --source . `
  --region $REGION `
  --allow-unauthenticated `
  --service-account $SA `
  --memory 1Gi --cpu 1 `
  --min-instances 0 --max-instances 1 `
  --set-env-vars "DB_URL=$DB_URL,DB_USERNAME=bgm_app,EMBEDDING_PROVIDER=openai,LLM_PROVIDER=openai,STT_PROVIDER=openai,TTS_PROVIDER=openai" `
  --set-secrets "DB_PASSWORD=bgm-db-password:latest,ADMIN_PASSWORD=bgm-admin-password:latest,EMBEDDING_API_KEY=bgm-openai-key:latest,LLM_API_KEY=bgm-openai-key:latest,STT_API_KEY=bgm-openai-key:latest,TTS_API_KEY=bgm-openai-key:latest"
```

When it finishes, gcloud prints the service URL. Open it: the home page shows Ticket to Ride.

Why these settings:

- `--max-instances 1` keeps the AI caps app-wide (they are counted in memory) and stays well within
  the small database's connection limit.
- `--min-instances 0` lets the app sleep when nobody uses it, so the first visit after a quiet
  period takes several seconds while Spring Boot starts.

If the build or deploy stops with a permission error, the message names the missing role and
account; grant it and run the command again.

## 5. Sign in to the admin area

Open `https://<your-url>/admin`. The browser asks for a username and password:

- Username: `admin`
- Password: shown by `gcloud secrets versions access latest --secret=bgm-admin-password`

Then add games as described in the [admin guide](ADMIN_GUIDE.md). The admin pages and all
content-changing API calls (`POST`/`PUT` under `/api/v1/games` and `/api/v1/game-versions`) need
this password. If `ADMIN_PASSWORD` is ever missing, they are refused rather than left open.

## 6. Limits on AI requests

Rule questions, voice and narration can call OpenAI, so the `prod` profile caps them:

| Setting | Default | Meaning |
| --- | --- | --- |
| `PAID_REQUESTS_PER_CLIENT_PER_HOUR` | `20` | AI requests one visitor can make per hour |
| `PAID_REQUESTS_PER_DAY` | `200` | AI requests the whole app makes per day (UTC) |

Over a cap, players see a short "the Game Master needs a break" page; lessons and setup keep
working. Counters reset when the app restarts, which is why the OpenAI budget limit matters.
To change a cap:

```powershell
gcloud run services update board-game-master --region $REGION --update-env-vars PAID_REQUESTS_PER_DAY=100
```

To turn the AI features off without redeploying:

```powershell
gcloud run services update board-game-master --region $REGION `
  --update-env-vars EMBEDDING_PROVIDER=disabled,LLM_PROVIDER=disabled,STT_PROVIDER=disabled,TTS_PROVIDER=disabled
```

## 7. Updating the app

After code changes (and passing tests), deploy again. Settings and secrets from the first deploy
are kept:

```powershell
gcloud run deploy board-game-master --source . --region $REGION
```

New Flyway migrations run automatically on start. Cover images placed in
`src/main/resources/static/images/covers/` are built into the image, so redeploy after adding one.

Useful commands:

```powershell
gcloud run services logs read board-game-master --region $REGION --limit 100   # recent logs
gcloud run services describe board-game-master --region $REGION                # settings and URL
```

## 8. Costs

- **Cloud SQL is the main cost.** The instance runs all the time, even with no visitors. Check the
  current price for `db-f1-micro` and storage in Jakarta on the
  [Cloud SQL pricing page](https://cloud.google.com/sql/pricing).
- **Cloud Run** bills only while handling requests when `--min-instances 0`; light portfolio
  traffic usually stays within its free monthly allowance.
- **Cloud Build and Artifact Registry** charge little for occasional builds and a few stored images.
- **OpenAI** bills per request; your project budget limit caps it.

Set a budget alert in **Billing → Budgets & alerts** in the Google Cloud console.

To pause the database when you don't need the site (storage is still billed):

```powershell
gcloud sql instances patch bgm-db --activation-policy=NEVER    # stop
gcloud sql instances patch bgm-db --activation-policy=ALWAYS   # start again
```

## 9. Removing everything

This deletes the app, the database and all its data, and the secrets. It can't be undone.

```powershell
gcloud run services delete board-game-master --region $REGION
gcloud sql instances delete bgm-db
gcloud secrets delete bgm-db-password
gcloud secrets delete bgm-admin-password
gcloud secrets delete bgm-openai-key
gcloud iam service-accounts delete "bgm-run@$PROJECT.iam.gserviceaccount.com"
```

The built images remain in Artifact Registry (repository `cloud-run-source-deploy`); delete them
there if you no longer need them.
