# Legacy yt-dlp resolver, deployed on Render.
#
# NOTE: the Render service root directory must change from `server` to
# `server/python`, because the Java service now owns `server/`. Redeploy after
# the move or the build will fail to find requirements.txt.
#
# Superseded by the Java service in ../src. Kept alive on purpose: the Android
# app still uses it as its last-resort resolver until the Java service is
# deployed and proven. Do not add features here.