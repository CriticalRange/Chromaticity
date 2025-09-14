# Chromaticity - Minecraft Shader Mod

## Project Overview
Chromaticity is a Minecraft mod that translates shaderpacks to newer Vulkan GLSL versions, utilizes VulkanMod's shaderc compiler, and injects into VulkanMod's rendering pipeline to create a complete shader pipeline.

## Core Architecture
- **Shader Translation**: Convert OpenGL shaderpacks to Vulkan GLSL versions
- **shaderc Integration**: Utilize VulkanMod's shaderc compiler for compilation
- **Pipeline Injection**: Integrate directly into VulkanMod's rendering pipeline
- **Compatibility Layer**: Maintain support for existing shaderpack formats

## Development Guidelines
- Focus on shader translation and compatibility
- Integrate seamlessly with VulkanMod's existing architecture
- Maintain performance optimizations during translation
- Support popular shaderpack formats (OptiFine, Iris, etc.)

## Build Commands
- Build: `./gradlew build`
- Run client: `./gradlew runClient`
- Run server: `./gradlew runServer`
- Generate IDE files: `./gradlew genIntellijRuns` (for IntelliJ) or `./gradlew eclipse` (for Eclipse)

## Testing
- Run tests: `./gradlew test`
- Test shader translation: Convert various OpenGL shaderpacks
- Test pipeline injection: Verify proper integration with VulkanMod
- Performance testing: Benchmark translation overhead and rendering performance

## Key Areas
- Shader translation engine (GLSL to Vulkan GLSL)
- shaderc compiler integration
- VulkanMod API integration (from C:\Users\Ahmet\Documents\VulkanMod)
- Pipeline state management
- Resource handling for translated shaders
- Error handling and fallback mechanisms

## VulkanMod Integration Points
- Reference VulkanMod source at `C:\Users\Ahmet\Documents\VulkanMod` for:
  - Shader compilation interfaces and shaderc usage
  - Vulkan rendering pipeline architecture
  - Resource management and buffer handling
  - Command buffer and synchronization patterns
  - Memory allocation and descriptor management
  - Swapchain and presentation logic