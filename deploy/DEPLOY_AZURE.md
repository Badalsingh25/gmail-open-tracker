# Deploy on an Azure VM (Azure for Students, no card)

You end up with one always-on Linux VM running MySQL, the tracker and Caddy (automatic HTTPS) in Docker, reachable at a
free Azure name such as `badal-tracker.centralindia.cloudapp.azure.com`. No domain purchase is needed.

Credit: Azure for Students gives $100 for 12 months. A 2 GB VM costs roughly $15/month, so plan for 6 months or so,
and set a budget alert (step 9). If the credit runs out the VM stops and tracking stops with it.

## 0. Before you start
- The project must be on GitHub (private is fine). Run `git status`; nothing secret may be committed (see `.gitignore`).
- Push the latest code: `git add -A`, `git commit -m "Deploy"`, `git push`.

## 1. Activate Azure for Students
1. Open https://azure.microsoft.com/free/students and sign in with a Microsoft account, verifying with your school email.
2. In https://portal.azure.com check that the subscription is called **Azure for Students**.

## 2. Create the VM
Portal → **Virtual machines** → **Create** → **Azure virtual machine**.
- Subscription: Azure for Students. Resource group: **Create new** → `tracker-rg`.
- Virtual machine name: `tracker-vm`.
- Region: choose the nearest one the portal allows (try **Central India**). Student subscriptions only allow some regions
  and sizes; if one is refused, pick another from the list.
- Image: **Ubuntu Server 24.04 LTS - x64 Gen2** (22.04 is fine too).
- Size: choose one with **2 GiB RAM or more** (for example `Standard_B1ms` or `Standard_B2s`). Do not pick 1 GiB.
- Authentication: **SSH public key**, username `azureuser`, key pair name `tracker-key`. Create it; the browser downloads
  `tracker-key.pem`. Keep it safe: it is the only way in.
- Inbound ports: **Allow selected ports** and tick **SSH (22)**, **HTTP (80)** and **HTTPS (443)**.
- Disks: keep the defaults. Then **Review + create** → **Create** and wait until it says deployment complete.

## 3. Give it a fixed address and a name
1. Open the VM → **Overview** → click the **Public IP address** link.
2. Make sure **Assignment** is **Static** (Standard-SKU addresses already are). Save if you changed it.
3. Under **DNS name label** type a short name, for example `badal-tracker`, and **Save**.
4. Your host is now `badal-tracker.<region>.cloudapp.azure.com`. Write it down exactly: this is `TRACK_HOST`, and it must
   never change after you start sending tracked mail.

## 4. Connect from Windows
Open cmd and run (use your own paths and host):
```
icacls "C:\Users\badal\Downloads\tracker-key.pem" /inheritance:r
icacls "C:\Users\badal\Downloads\tracker-key.pem" /grant:r "%username%:R"
ssh -i "C:\Users\badal\Downloads\tracker-key.pem" azureuser@badal-tracker.<region>.cloudapp.azure.com
```
Answer `yes` to the fingerprint question. The prompt should now start with `azureuser@tracker-vm`.

## 5. Prepare the server (run these on the VM)
```
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
exit
```
Reconnect with the same `ssh` command. Check with `docker --version` and `free -h` (swap should show 2.0G).

## 6. Get the code
Create a read-only GitHub token: GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens**
→ Generate: repository access **Only select repositories** (this one), permission **Contents: Read-only**. Copy it.
```
git clone https://github.com/Badalsingh25/gmail-open-tracker.git
cd gmail-open-tracker/deploy
```
When asked, the username is `Badalsingh25` and the password is the token.

## 7. Configure
```
cp azure.env.example .env
nano .env
```
- `TRACK_HOST` = your Azure host from step 3.
- Fill each secret with a different value from `openssl rand -hex 32` (run it four times).
- Save in nano with Ctrl+O, Enter, then Ctrl+X.
- Write down `TRACKER_API_KEY` (you type it into the extension) and keep a safe copy of `TRACKER_SIGNING_SECRETS`.
  Losing the signing secret breaks every email already sent.

## 8. Start it
```
docker compose -f docker-compose.azure.yml up -d --build
docker compose -f docker-compose.azure.yml ps
```
The first build takes about 5 to 10 minutes on a small VM. Wait until `app` and `mysql` show **healthy**.
If `app` keeps restarting, run `docker compose -f docker-compose.azure.yml logs app --tail 80` and send me the output.

Check from your own computer:
- `https://<TRACK_HOST>/api/health` shows `"status":"ok"` (the first load can take a few seconds while Caddy gets the certificate).
- `https://<TRACK_HOST>/api/stats` without a key returns 401. That is correct.

## 9. Safety nets
- **Budget alert:** portal → **Cost Management + Billing** → **Budgets** → create a monthly budget with an email alert.
- **Backups:** run `chmod +x backup.sh` then `crontab -e` and add the line
  `0 3 * * * /home/azureuser/gmail-open-tracker/deploy/backup.sh`.
  Test it once with `./backup.sh` and look in `~/backups`.
- **Updates:** `cd ~/gmail-open-tracker && git pull && cd deploy && docker compose -f docker-compose.azure.yml up -d --build`.
- **Looking at data:** `docker compose -f docker-compose.azure.yml exec mysql mysql -utracker -p email_tracker`.

## 10. Point the extension at it
Extension **Settings**: API URL `https://<TRACK_HOST>`, API key = `TRACKER_API_KEY` → **Save** (Chrome asks for permission
to contact the host) → **Test connection**. Then refresh Gmail and send a new tracked mail to your other account.

## 11. Check it works (important)
Open that mail in the other account, then look at the open history in the popup. The evidence should say "Gmail image
proxy fetch". If it says "the address is not Google's", the client address is being read wrongly; tell me.
