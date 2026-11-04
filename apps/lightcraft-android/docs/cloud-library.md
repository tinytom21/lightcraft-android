# Cloud library on the phone

A PC publishes a photo folder to cloud storage with the
[LightCraft Cloud](https://github.com/tinytom21/lightcraft-cloud) desktop app (the remote layout is in its
[FORMAT.md](https://github.com/tinytom21/lightcraft-cloud/blob/main/FORMAT.md)). The phone then shows the photos
as thumbnails, downloads a small smart preview of each photo you open, lets you edit it, and sends
the edit back to the PC, which stays the master copy. The phone never holds the originals unless you
ask for a full-size export.

The phone reaches the storage through [rclone](https://rclone.org), built into the app, so any
rclone backend works (Google Drive, OneDrive, Dropbox, S3, WebDAV, SFTP, a folder…). The easy way
to give the phone access is to **pair** it with the PC; the manual way, with an rclone config file,
is kept below.

## Pair the phone with your PC

1. On the phone, open Cloud library ▸ **Pair with your PC**. The phone shows a code in large type,
   like `7K2M-9QXD-4TB`. Leave the page open: a new code is made every time you open it.
2. On the home PC, open LightCraft Cloud ▸ Status ▸ Add a device, and type that code into the code
   field (the field hides what you type). Press **Show pairing QR**. If the PC says the code has a
   typo, check it against the phone.
3. On the phone, choose **Scan the QR code** and point the camera at the PC's screen. (No camera, or
   the scan fails: choose **Paste the text instead**, and paste the text the window's Copy text
   button gave you. It starts with `LCP1`.) You type no code on the phone.
4. If you like, change the name of this phone (letters, digits, `_`, `-` and `.`, up to 64; the PC
   files its edits under that name, so two phones need two names). Choose **Connect**.

Why this order: the home PC's screen only ever shows the locked QR code, never the code. A screen
share or a photo of the PC's screen therefore gives nobody anything: the code lives on the phone
and in the box you typed it into. The phone opens the QR code's contents with the code it already
holds (about a second), saves them in the app's private storage, connects and loads the photos.
The text and the code are not kept: they are dropped as soon as the phone is paired, or when you
leave the page, and are never logged. The camera permission is asked for only when you choose Scan
the QR code (and for the camera button). If a phone of the same name has already sent edits to this
library, the app says so; disconnect and pair again with another name.

## Use an rclone config file instead

If you can't pair (a PC without the Add a device window, a remote you make yourself), the app
needs one **rclone config file** that contains one remote: a *phone-only* remote. On the Cloud
library page choose "Use an rclone config file instead".

### Make a phone-only remote

Do this on the PC, where rclone is installed. Use a **separate remote** for the phone, not the one the
PC publishes with, so the phone can be cut off without touching the PC.

#### Google Drive: limit it to one folder

1. In Drive, open the folder the PC publishes into (the one that holds `originals/`, `mobile/`,
   `inbox/`). Its address ends in `/folders/<folder id>`; copy that id.
2. Create a remote that starts at that folder:

   ```sh
   rclone config create phone drive scope=drive root_folder_id=<folder id>
   ```

   (or `rclone config`, then `n`, type `drive`, scope `1`, and paste the id as `root_folder_id`). The
   browser asks you to sign in and allow rclone.
3. Check that it sees the library and nothing else:

   ```sh
   rclone lsd phone:          # originals, mobile, inbox, ...
   ```

   The remote's root *is* the library, so in the app the remote is `phone:`.

#### Other backends: a path

Make the remote as usual (`rclone config`) and give the app the path of the library on it:
`phone:LightCraft Cloud` for S3 or WebDAV, `phone:photos/cloud` for SFTP.

### Export just that remote to a file

`rclone config show` prints one remote (the section and its token), nothing else:

```sh
rclone config show phone > phone-rclone.conf
```

Copy `phone-rclone.conf` to the phone (a USB cable or a share you trust) and pick it in the app's
cloud settings together with the remote (`phone:`) and a name for this phone (letters, digits, `_`,
`-` and `.`, like `pixel-9`; the PC files its edits under that name). Delete the copy on the PC
afterwards if you like; the app keeps its own.

Do not use `~/.config/rclone/rclone.conf` itself: it holds every remote you have, including the
PC's.

**Encrypted library (rclone `crypt`):** the phone needs the crypt section as well as `phone`, and
`rclone config show` prints its passwords as `*** ENCRYPTED ***`, which the app can't decrypt with.
Copy that section from the config file instead, and pick the crypt remote in the app; see
[Encrypting the remote](https://github.com/tinytom21/lightcraft-cloud#encrypting-the-remote).

### What the file lets the phone do

The phone writes only to `inbox/<its name>/`, as new files that are never overwritten; the PC
validates every one before applying it. It reads thumbnails, previews, the manifest and, for a
full-size export, originals.

**Security.** The file holds an OAuth token. For Google Drive that token carries the scope you picked
(`drive`, the whole Drive), because Google offers no per-folder scope that rclone can use. What
confines the phone to the one folder is rclone: `root_folder_id` makes that folder the remote's root,
and the app only talks to that remote. Someone who gets the file and reads the token could use it
against all your Drive with other software. Treat `phone-rclone.conf` like a password: keep it off
shared storage, and if the phone is lost, revoke it at
<https://myaccount.google.com/permissions> (remove "rclone"), which also cuts off the PC's remote if
it uses the same rclone client; to avoid that, create the phone remote with its own client id
(`client_id` / `client_secret` from your own Google Cloud project) so the two tokens can be revoked
separately. A `drive.file`-scoped token would be narrower, but it only sees files the same app
created, which does not include the PC's uploads.

## In the app

Connect or pair (the app checks it can reach the library) and the photos load; Refresh: thumbnails appear, and the first
time you open a photo its preview downloads (about 2 to 3 MB). Edits are written to the photo's XMP
sidecar on the phone and sent to the PC when you push (or automatically, once the UI does so); with
no network they wait and go later. If the PC changed a photo's edits you have not sent yet, the PC's
version is not applied until yours is sent; the PC then keeps whichever edit is newer. After the PC
has applied an edit it publishes it, and the next refresh shows it.

Smart previews are cached up to a size you can set (2 GiB by default); the least recently opened
are deleted first and downloaded again when needed.

Raw + JPEG pairs of one shot are one photo: the app lists the partner too, but hides it
(`cloud_hidden_photos`).

## Albums from the PC

The PC publishes its LightCraft albums (the manifest's `albums`, see FORMAT.md in the LightCraft Cloud repository), and each refresh
mirrors them into the phone's own library: an album folder called **From PC** at the top level, with the PC's folders inside it and the
albums in those, in the albums list of the title menu like any others. They are ordinary albums of the photos the phone has records for
(a photo outside the synced folder, or one the phone does not have, is left out; a raw partner of a pair is the pair's one photo). A
smart album on the PC comes as an ordinary album holding the photos that matched when the PC last read it.

**They are view-only.** The PC is the master: every refresh makes **From PC** match the manifest again. Rename or delete a mirrored
album, add a photo to it or take one out, make an album inside the folder: the next refresh puts it back (the folder and everything in
it that the PC does not list is deleted; the photos stay in your library). A rename on the PC renames the album here, and it keeps its
id. When the PC stops publishing albums the folder goes. Albums you make yourself, anywhere outside **From PC**, are never read for
anything but their place in the tree, and never changed: to keep a mirrored album's photos in an album of your own, add them to your
album. Do not call a folder of your own **From PC**: the app takes the top-level folder with that name for its own if it lost track of
its folder.

The mirroring uses the phone engine's own album commands (`album.create`, `album.rename`, `album.reorder`, `album.addPhotos`,
`album.removePhotos`, `album.delete`) on the phone's catalog and keeps a map from the PC's album ids to the phone's in the cloud state
(`state.json`). `album.addPhotos` and `album.removePhotos` only run while something is selected, so the first photo of the change is
selected for the call and your selection is put back afterwards. A failure is listed in the refresh's `failures` and tried again at
the next refresh.

## Building the app

The app contains rclone's C library, which Gradle builds with Go (pinned in
`librclone/GO_VERSION`; `scripts/install-go.sh` installs it) and packages itself. A release build
needs that exact Go; a debug build without any Go works but can't open a cloud library. See
"librclone" in the app's README.
