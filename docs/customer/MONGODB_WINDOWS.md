# MongoDB on Windows (D drive)

This guide configures MongoDB to run as a Windows service with data and logs on D:\MongoDB.

## 1) Install MongoDB Server and mongosh
- Install MongoDB Server (MSI)
- Install MongoDB Shell (mongosh)
- Choose "Run as a Service" during install

## 2) Create data and log directories
```
New-Item -ItemType Directory -Force D:\MongoDB\data | Out-Null
New-Item -ItemType Directory -Force D:\MongoDB\log | Out-Null
```

## 3) Grant the service account access
Find the service account:
```
Get-CimInstance Win32_Service -Filter "Name='MongoDB'" | Select-Object Name,StartName
```
Then grant permissions (example for NetworkService):
```
icacls D:\MongoDB\data /grant "NT AUTHORITY\NetworkService:(OI)(CI)F"
icacls D:\MongoDB\log /grant "NT AUTHORITY\NetworkService:(OI)(CI)F"
```

## 4) Update mongod.cfg
Typical config file:
- D:\Program Files\bin\mongod.cfg

Set these values:
```
storage:
  dbPath: D:\MongoDB\data

systemLog:
  destination: file
  logAppend: true
  path: D:\MongoDB\log\mongod.log

net:
  port: 27017
  bindIp: 127.0.0.1

security:
  authorization: enabled
```

## 5) Start the service
```
Start-Service MongoDB
```

## 6) Create admin and app users (localhost exception)
Run from the same host:
```
mongosh
use admin

db.createUser({
  user: "admin",
  pwd: "<ADMIN_PASSWORD>",
  roles: [
    { role: "userAdminAnyDatabase", db: "admin" },
    { role: "readWriteAnyDatabase", db: "admin" }
  ]
})

db.auth("admin", "<ADMIN_PASSWORD>")

use mercenary

db.createUser({
  user: "mercenary_app",
  pwd: "<APP_PASSWORD>",
  roles: [ { role: "readWrite", db: "mercenary" } ]
})
```

## 7) Set the app connection string
```
mongodb://mercenary_app:<APP_PASSWORD>@localhost:27017/mercenary
```
If the password contains special characters, URL encode it.

## 8) Verify login
```
mongosh --username mercenary_app --password "<APP_PASSWORD>" --authenticationDatabase mercenary --host localhost --port 27017
```

## Notes
- If authentication fails with "storedKey mismatch", the password does not match the stored user.
- If you do not know the admin password, restore from backup or reset the data directory.
