# Base image
FROM python:3.9-slim

# Set working directory
WORKDIR /app

# Install system dependencies (Poppler for pdf2image, GL/GLib for OpenCV)
RUN apt-get update && apt-get install -y \
    poppler-utils \
    libglib2.0-0 \
    libgl1-mesa-glx \
    && rm -rf /var/lib/apt/lists/*

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# Install uvicorn if not in requirements (it should be, but just in case)
RUN pip install uvicorn

# Copy application code
COPY . .

# Expose port
EXPOSE 8000

# Run the application
CMD ["uvicorn", "search_intelligence:app", "--host", "0.0.0.0", "--port", "8000"]
