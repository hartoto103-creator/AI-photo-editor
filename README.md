An Android photo editor with AI-powered object removal and image inpainting.

Features
- AI Object Removal
- Brush Selection
- Lasso Selection
- Interactive Object Segmentation
- AI Inpainting with LaMa
- Undo / Redo
- Image Zoom & Pan

Tech Stack
- Kotlin
- Jetpack Compose
- ONNX Runtime
- LaMa Inpainting
- MediaPipe Interactive Segmentation

Status

Currently in development.
The core object removal and AI inpainting pipeline is working,
while image quality, mask refinement, and performance are still
being improved.

Architecture

The application uses local AI inference for image processing,
allowing object removal without requiring a cloud server.

Issues

- Inpainting quality can vary depending on the image.
- Some complex objects may leave visible artifacts.
- Lasso selection is still being refined.
- Processing time can vary for high-resolution images.
