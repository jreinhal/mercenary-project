# MongoDB on Linux

This guide configures MongoDB on common Linux distributions for SENTINEL.

## 1) Install MongoDB
Use your distribution's MongoDB package. For air-gapped installs, stage packages locally.

## 2) Create data and log directories
```
sudo mkdir -p /var/lib/mongodb
sudo mkdir -p /var/log/mongodb
sudo chown -R mongodb:mongodb /var/lib/mongodb /var/log/mongodb
```

## 3) Update mongod.conf
Typical config file:
- /etc/mongod.conf

Recommended settings:
```
storage:
  dbPath: /var/lib/mongodb

systemLog:
  destination: file
  logAppend: true
  path: /var/log/mongodb/mongod.log

net:
  port: 27017
  bindIp: 127.0.0.1

security:
  authorization: enabled
```

## 4) Start and enable service
```
sudo systemctl enable mongod
sudo systemctl restart mongod
sudo systemctl status mongod
```

## 5) Create admin and app users (localhost exception)
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

## 6) Set the app connection string
```
mongodb://mercenary_app:<APP_PASSWORD>@localhost:27017/mercenary
```
If the password contains special characters, URL encode it.

## 7) Verify login
```
mongosh --username mercenary_app --password "<APP_PASSWORD>" --authenticationDatabase mercenary --host localhost --port 27017
```

## Notes
- If authentication fails with "storedKey mismatch", the password does not match the stored user.
- If you do not know the admin password, restore from backup or reset the data directory.
