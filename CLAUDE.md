# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
Chromaticity is a Fabric-based Minecraft mod that translates OpenGL shaderpacks to Vulkan GLSL versions and integrates with VulkanMod's rendering pipeline.

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
**Windows:**
```bash
# Build the mod
.\gradlew build

# Run Minecraft client with mod
.\gradlew runClient

# Run Minecraft server with mod
.\gradlew runServer

# Generate IntelliJ IDEA run configurations
.\gradlew genIntellijRuns

# Generate Eclipse project files
.\gradlew eclipse

# Clean build artifacts
.\gradlew clean

# Test the mod
.\gradlew test
```

**Unix/Linux/macOS:**
```bash
# Build the mod
./gradlew build

# Run Minecraft client with mod
./gradlew runClient

# Run Minecraft server with mod
./gradlew runServer

# Generate IntelliJ IDEA run configurations
./gradlew genIntellijRuns

# Generate Eclipse project files
./gradlew eclipse

# Clean build artifacts
./gradlew clean

# Test the mod
./gradlew test
```

## Testing
- **Unit Tests**: `./gradlew test` (basic Gradle test setup)
- **Integration Testing**: Use `./gradlew runClient` for manual testing
- **Shader Translation Testing**: Convert various OpenGL shaderpacks
- **Pipeline Injection Testing**: Verify proper integration with VulkanMod
- **Performance Testing**: Benchmark translation overhead and rendering performance
- **Development Logs**: Check `run/` directory for crash reports and debug information

## Shader Packs Button Implementation
- **Mixin Target**: VOptionScreen.init() method injection
- **Implementation**: ✅ COMPLETED - Full working mixin integration with VulkanMod
- **Current Status**: ✅ Production-ready button successfully integrated
- **Features**:
  - Adds "Shader Packs" button positioned to the left of "Support me" button
  - Dynamically calculates position using reflection to access supportButton field
  - Uses VulkanMod's native widget system for proper rendering and event handling
  - Clean integration without console logging or debug output
  - Graceful error handling with silent fallback
  - Ready for future shader pack management functionality
- **Technical Implementation**:
  - Uses `@Inject(method = "init", at = @At("RETURN"))` to inject after VOptionScreen initialization
  - Accesses private `supportButton` field via reflection to get positioning
  - Calculates position: `supportButton.getX() - buttonWidth - spacing`
  - Adds button to VulkanMod's `buttons` list for proper rendering
  - No dependency on Minecraft's Screen.addWidget() method (avoids reflection complexity)
- **Build Configuration**:
  - ✅ VulkanMod dependency: `modImplementation "maven.modrinth:vulkanmod:0.5.5"`
  - ✅ Mojang mappings: `mappings loom.officialMojangMappings()`
  - ✅ Mixin registered in chromaticity.mixins.json under client array
- **Development Journey**:
  - **Phase 1**: Started with complex reflection service approach
  - **Phase 2**: Migrated from Yarn to Mojang mappings for VulkanMod compatibility
  - **Phase 3**: Switched to mixin approach per user feedback
  - **Phase 4**: Resolved method signature issues with GuiEventListener
  - **Phase 5**: Simplified to use only VulkanMod's button system (removed addWidget)
  - **Phase 6**: Implemented dynamic positioning based on Support Me button
  - **Phase 7**: Cleaned up logging for production readiness

## Architecture Overview
**Package Structure:**
```
src/main/java/net/chromaticity/
├── Chromaticity.java              # Main mod entry point
├── config/
│   └── option/
│       └── ChromaticityOptions.java  # Shader configuration management
├── handler/                       # Event and service handlers
├── mixin/
│   └── screen/
│       └── VOptionScreenMixin.java  # ✅ VulkanMod UI integration (WORKING)
└── service/                       # Business logic services
```

**Key Components:**
- **VOptionScreenMixin**: ✅ Production-ready UI injection into VulkanMod's configuration screen
- **ChromaticityOptions**: Shader pack management (placeholder implementation)
- **Chromaticity.java**: Simplified main entry point with VulkanMod integration via mixin
- **Clean Architecture**: Removed complex service layer in favor of direct mixin approach

**Removed Components (Development History):**
- **VulkanModIntegrationService**: Complex reflection service (replaced by mixin)
- **ExampleMixin**: Template mixin (removed to clean up build warnings)

## Development Environment
- **Minecraft Version**: 1.21.1
- **Java Version**: 21 (required)
- **Fabric Loader**: ≥0.17.2
- **Fabric API**: 0.116.6+1.21.1
- **Memory Settings**: Gradle configured with 4GB JVM memory
- **IDE Support**: IntelliJ (`genIntellijRuns`) and Eclipse (`eclipse`)
- **Run Directory**: `run/` folder for Minecraft instance and logs

## Key Development Areas
- Shader translation engine (GLSL to Vulkan GLSL)
- shaderc compiler integration
- VulkanMod API integration (from C:\Users\Ahmet\Documents\VulkanMod)
- Pipeline state management
- Resource handling for translated shaders
- Error handling and fallback mechanisms

## VulkanMod Integration Reference
Reference VulkanMod source at `C:\Users\Ahmet\Documents\VulkanMod` for:
- Shader compilation interfaces and shaderc usage
- Vulkan rendering pipeline architecture
- Resource management and buffer handling
- Command buffer and synchronization patterns
- Memory allocation and descriptor management
- Swapchain and presentation logic

## Troubleshooting & Common Issues

### Mapping Compatibility Issues
- **Problem**: ClassNotFoundException for Component, GuiEventListener classes
- **Solution**: Use Mojang mappings (`loom.officialMojangMappings()`) to match VulkanMod
- **Migration**: Run `./gradlew migrateMappings --mappings "1.21.1+build.3"`

### Mixin Injection Issues
- **Problem**: InvalidInjectionException for non-existent methods
- **Solution**: Only inject into methods that exist in target class (avoid renderWidget)
- **Best Practice**: Use `@At("RETURN")` for post-initialization injection

### Widget System Integration
- **Problem**: Buttons created but not visible on screen
- **Solution**: Add to VulkanMod's `buttons` list, not just Minecraft's widget system
- **Implementation**: Use reflection to access private `buttons` field

### Reflection Method Signatures
- **Problem**: NoSuchMethodException for generic methods
- **Solution**: Use base interface types (GuiEventListener.class) instead of Object.class
- **Import Path**: `net.minecraft.client.gui.components.events.GuiEventListener`

### Build Configuration
- **VulkanMod Dependency**: `modImplementation "maven.modrinth:vulkanmod:0.5.5"`
- **Modrinth Repository**: Required in repositories block for VulkanMod
- **Mappings**: Must use Mojang mappings for compatibility
- **Mixin Registration**: Add to `chromaticity.mixins.json` under `client` array

### Development Workflow
1. **Always test mixin injection first** with simple console output
2. **Use reflection carefully** - prefer mixin shadows when possible
3. **Handle exceptions gracefully** - silent fallback for missing dependencies
4. **Remove debug logging** before production builds
5. **Test positioning dynamically** using existing UI elements as reference points