package net.chromaticity.test;

import net.chromaticity.shader.preprocessing.GlslVersionTranslator;
import net.chromaticity.shader.preprocessing.VulkanModIntegration;

/**
 * Simple test to verify enhanced SPIR-V preprocessing functionality.
 */
public class PreprocessingTest {

    public static void main(String[] args) {
        System.out.println("=== Enhanced SPIR-V Preprocessing Test ===");

        testVertexShaderProcessing();
        testFragmentShaderProcessing();
        testVulkanModBindings();

        System.out.println("=== All Tests Completed Successfully! ===");
    }

    private static void testVertexShaderProcessing() {
        System.out.println("\n1. Testing Vertex Shader Processing...");

        GlslVersionTranslator translator = new GlslVersionTranslator();

        String inputShader = """
            #version 400 compatibility

            attribute vec3 gl_Vertex;
            attribute vec3 gl_Normal;
            attribute vec2 gl_MultiTexCoord0;

            varying vec2 texCoord;
            varying vec3 normal;

            uniform mat4 gl_ModelViewMatrix;
            uniform mat4 gl_ProjectionMatrix;
            uniform sampler2D diffuseTexture;

            void main() {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                texCoord = gl_MultiTexCoord0;
                normal = gl_Normal;

                vec4 color = texture2D(diffuseTexture, texCoord);
            }
            """;

        String result = translator.translateShader(inputShader, GlslVersionTranslator.ShaderType.VERTEX);

        // Verify key transformations
        assert result.contains("#version 450 core") : "Should upgrade to GLSL 450 core";
        assert result.contains("#extension GL_GOOGLE_include_directive : enable") : "Should add include directive";
        assert result.contains("layout(location =") : "Should add layout qualifiers";
        assert result.contains("inPosition") : "Should replace gl_Vertex";
        assert result.contains("inNormal") : "Should replace gl_Normal";
        assert result.contains("inTexCoord0") : "Should replace gl_MultiTexCoord0";
        assert result.contains("layout(binding = 0, std140) uniform MatrixUniforms") : "Should add matrix uniform block";
        assert result.contains("texture(") && !result.contains("texture2D(") : "Should modernize texture functions";

        System.out.println("✓ Vertex shader processing test passed!");
        System.out.println("Key transformations verified:");
        System.out.println("  - GLSL 450 core upgrade");
        System.out.println("  - Layout qualifiers for vertex attributes");
        System.out.println("  - Built-in variable replacement");
        System.out.println("  - Matrix uniform block generation");
        System.out.println("  - Texture function modernization");
    }

    private static void testFragmentShaderProcessing() {
        System.out.println("\n2. Testing Fragment Shader Processing...");

        GlslVersionTranslator translator = new GlslVersionTranslator();

        String fragmentShader = """
            #version 120

            varying vec2 texCoord;
            uniform sampler2D diffuseTexture;

            void main() {
                vec4 color = texture2D(diffuseTexture, texCoord);
                gl_FragColor = color;
            }
            """;

        String result = translator.translateShader(fragmentShader, GlslVersionTranslator.ShaderType.FRAGMENT);

        assert result.contains("layout(location = 0) out vec4 fragColor") : "Should add fragColor output";
        assert result.contains("in vec2 texCoord") : "Should transform varying to in";
        assert result.contains("fragColor =") : "Should replace gl_FragColor";

        System.out.println("✓ Fragment shader processing test passed!");
        System.out.println("Key transformations verified:");
        System.out.println("  - Fragment output declaration");
        System.out.println("  - Varying to in/out transformation");
        System.out.println("  - gl_FragColor replacement");
    }

    private static void testVulkanModBindings() {
        System.out.println("\n3. Testing VulkanMod Binding Analysis...");

        String shaderWithUniforms = """
            #version 450 core

            uniform sampler2D diffuseTexture;
            uniform sampler2D normalTexture;
            uniform float time;
            uniform vec3 cameraPosition;

            void main() {
                // shader content
            }
            """;

        VulkanModIntegration.VulkanBindingAnalysis analysis =
            VulkanModIntegration.analyzeShaderBindings(shaderWithUniforms, GlslVersionTranslator.ShaderType.FRAGMENT);

        assert analysis.getSamplers().size() == 2 : "Should detect 2 samplers";
        assert analysis.getUnboundUniforms().size() >= 2 : "Should detect unbound uniforms";

        String resultWithBindings = VulkanModIntegration.applyVulkanModBindings(shaderWithUniforms, analysis);

        assert resultWithBindings.contains("layout(binding =") : "Should add binding qualifiers";

        System.out.println("✓ VulkanMod binding analysis test passed!");
        System.out.println("Key features verified:");
        System.out.println("  - Sampler detection and binding assignment");
        System.out.println("  - Uniform analysis and binding conventions");
        System.out.println("  - VulkanMod-specific binding patterns");
    }
}