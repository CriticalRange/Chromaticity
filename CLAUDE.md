# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
Chromaticity is a Fabric-based Minecraft mod that translates OpenGL shaderpacks to Vulkan GLSL versions and integrates with VulkanMod's rendering pipeline. The mod provides a seamless bridge between existing OptiFine/Iris shaderpacks and VulkanMod's Vulkan-based renderer.

## Core Architecture

### Shader Translation Pipeline
- **OpenGL → GLSL 450 Core**: Converts legacy OpenGL shaders to modern GLSL 450 core
- **GLSL → SPIR-V**: Compiles preprocessed shaders to SPIR-V bytecode using DirectShaderCompiler
- **VulkanMod Integration**: Injects compiled shaders into VulkanMod's rendering pipeline
- **Cache Management**: Maintains processed shader cache with change tracking

### Key Components
- **ShaderPackManager**: Core service managing shader pack discovery, extraction, and processing
- **ShaderCompilationService**: Orchestrates the full compilation pipeline with DirectShaderCompiler
- **ShaderTranslationService**: Handles OpenGL to Vulkan GLSL translation
- **VOptionScreenMixin**: UI integration that adds "Shader Packs" button to VulkanMod's settings
- **ShaderPackScreen**: Complete shader pack management interface with settings and application

### UI Integration
- **VulkanMod Mixin**: Injects shader pack button into VulkanMod's VOptionScreen using reflection
- **Dynamic Settings**: Parses shader options from both .properties files and GLSL #define directives
- **Real-time Application**: Processes and applies shader packs without restart requirement

## Build Commands

**Windows:**
```bash
# Build the mod
.\gradlew build

# Run Minecraft client with mod
.\gradlew runClient

# Run tests
.\gradlew test

# Clean build artifacts
.\gradlew clean

# Generate IDE configurations
.\gradlew genIntellijRuns
.\gradlew eclipse
```

**Unix/Linux/macOS:**
```bash
# Build the mod
./gradlew build

# Run Minecraft client with mod
./gradlew runClient

# Run tests
./gradlew test

# Clean build artifacts
./gradlew clean

# Generate IDE configurations
./gradlew genIntellijRuns
./gradlew eclipse
```

## Development Environment
- **Minecraft Version**: 1.21.1
- **Java Version**: 21 (required)
- **Fabric Loader**: ≥0.17.2
- **Fabric API**: 0.116.6+1.21.1
- **VulkanMod**: 0.5.5 (required dependency)
- **Mappings**: Mojang Official (for VulkanMod compatibility)
- **Memory**: 4GB JVM heap allocated via gradle.properties

## Package Structure

```
src/main/java/net/chromaticity/
├── Chromaticity.java                    # Main mod entry point
├── mixin/screen/
│   └── VOptionScreenMixin.java         # VulkanMod UI integration
├── shader/
│   ├── ShaderPackManager.java          # Core shader pack management
│   ├── compilation/
│   │   ├── ShaderCompilationService.java
│   │   └── DirectShaderCompiler.java
│   ├── preprocessing/
│   │   ├── ShaderTranslationService.java
│   │   ├── GlslVersionTranslator.java
│   │   └── VulkanModIntegration.java
│   ├── option/                         # Shader option parsing system
│   └── properties/                     # Legacy .properties support
├── screen/
│   ├── ShaderPackScreen.java          # Main shader pack GUI
│   ├── ShaderPackSettingsScreen.java  # Shader settings interface
│   └── dynamic/                       # Dynamic UI generation
└── config/                           # Configuration management
```

## Key Dependencies

### Required
- **VulkanMod 0.5.5**: Core Vulkan renderer integration
- **LWJGL shaderc**: SPIR-V compilation support (version 3.3.3)
- **Fabric API**: Standard Fabric mod functionality

### Development
- **JUnit Jupiter 5.10.0**: Unit testing framework
- **Mixin**: Bytecode injection for VulkanMod integration

## Shader Pack Cache Structure

Located in `run/shaderpacks/chromaticity_cache/[pack_name]/`:
- **original/**: Extracted original shader pack files
- **updated/**: Preprocessed GLSL 450 core shaders (ready for SPIR-V compilation)
- **spir-v/**: SPIR-V bytecode output
- **shaderpack_settings.json**: Parsed shader options and user settings

## Testing Strategy

### Unit Tests
```bash
./gradlew test
```

### Integration Testing
**IMPORTANT**: Claude Code should NEVER run `./gradlew runClient` or any game testing commands. The user will handle all game testing and execution.

```bash
# Manual testing with Minecraft client (USER ONLY)
./gradlew runClient
```

### Shader Translation Testing
- Place test shaderpacks in `run/shaderpacks/`
- Use in-game "Shader Packs" button to test translation pipeline
- Check `run/logs/latest.log` for compilation results
- Verify cache structure: `original/` → `updated/` → `spir-v/` in `run/shaderpacks/chromaticity_cache/`

## VulkanMod Integration Details

### Mixin Implementation
- **Target**: `VOptionScreen.init()` method injection
- **Positioning**: Dynamic placement left of "Support me" button using reflection
- **Button System**: Uses VulkanMod's native VButtonWidget for consistent styling
- **Error Handling**: Silent fallbacks for missing dependencies

### Include Path Configuration
- Automatically configures DirectShaderCompiler with shaderpack-specific include paths
- Supports nested directories: `/lib/`, `/include/`, custom shader subdirectories
- Integrates with VulkanMod's existing shader compilation infrastructure

## Troubleshooting

### Common Issues

**Mapping Compatibility**
- **Problem**: ClassNotFoundException for VulkanMod classes
- **Solution**: Ensure using Mojang mappings (`loom.officialMojangMappings()`)
- **Migration**: `./gradlew migrateMappings --mappings "1.21.1+build.3"`

**Shader Compilation Failures**
- **Problem**: SPIR-V compilation errors
- **Debug**: Check `run/logs/latest.log` for detailed compilation output
- **Common**: Missing include files or invalid GLSL syntax after translation

**Mixin Injection Issues**
- **Problem**: UI button not appearing
- **Debug**: Verify VulkanMod dependency is loaded
- **Solution**: Check mixin registration in `chromaticity.mixins.json`

## Development Workflow

1. **Shader Pack Testing**: Place test shaderpacks in `run/shaderpacks/`
2. **UI Development**: Test mixin integration with `./gradlew runClient`
3. **Translation Pipeline**: Monitor cache generation and SPIR-V output
4. **Performance Testing**: Use large shaderpacks to test compilation batching
5. **VulkanMod Integration**: Ensure compatibility with VulkanMod updates

## Code Style Guidelines

### Naming Conventions
- **NEVER add temporary prefixes** like "New", "Old", "Temp", "Test" to class names
- Use descriptive, final names from the start (e.g., `DirectShaderCompilerPreprocessing` not `DirectShaderCompilerNew`)
- If replacing a class, rename the old one first (e.g., `DirectShaderCompiler` -> `DirectShaderCompilerLegacy`)
- Choose names that clearly indicate the implementation approach or purpose

### Implementation Standards
- **NO MOCK IMPLEMENTATIONS**: All code must be real, functional implementations
- No placeholder, stub, or mock versions of files/classes
- Implement complete, working solutions rather than temporary workarounds
- Focus on production-quality code that solves actual problems

## Reference Materials

### VulkanMod Integration
- Reference VulkanMod source at `C:\Users\Ahmet\Documents\VulkanMod` for:
  - DirectShaderCompiler API usage
  - VulkanMod UI component patterns
  - SPIR-V compilation pipeline integration
  - Shader resource management

### Shader Translation
- OpenGL to Vulkan GLSL compatibility matrix
- SPIR-V compilation requirements and include path handling
- Shader option parsing from both .properties and GLSL #define directives
- Don't do tests