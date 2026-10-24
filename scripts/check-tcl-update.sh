#!/bin/sh
# TCL firmware updates live in com.tcl.ttvs, which the cleanup disables (it also carries Samba TV
# tracking). This turns it on, opens System Update on the TV, and turns it off again when you are done.
set -e
. "$(dirname "$0")/env.sh"
tv "pm enable com.tcl.ttvs; am start -n com.tcl.ttvs/.versionUpdateApp.core.UI.SystemUpdateActivity"
printf 'System Update is open on the TV. Install any update there, then press Enter here to disable the updater again. '
read _
tv "pm disable-user --user 0 com.tcl.ttvs"
