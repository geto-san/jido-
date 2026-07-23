import subprocess
import json
from fastapi import FastAPI, HTTPException, Query
from typing import Optional

app = FastAPI()

@app.get("/")
def read_root():
    return {"status": "ok", "message": "Jido yt-dlp resolver is running"}

@app.get("/resolve")
def resolve_url(url: str = Query(..., description="The URL to resolve")):
    try:
        # Use yt-dlp to get the direct URL
        # --get-url returns the media URL
        # --format best fetches the best single-file format (usually mp4)
        command = [
            "yt-dlp",
            "-g",
            "--format", "best",
            url
        ]

        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        stdout, stderr = process.communicate()

        if process.returncode != 0:
            raise HTTPException(status_code=400, detail=f"yt-dlp error: {stderr.strip()}")

        direct_url = stdout.strip().split('\n')[0]

        if not direct_url:
            raise HTTPException(status_code=404, detail="No direct URL found")

        return {
            "status": "success",
            "url": direct_url,
            "platform": "yt-dlp"
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
