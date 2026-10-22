// Texture swap chains of the VrApi layer.
//
// A VrApi game allocates a chain, renders into whichever image it likes and names that index at
// submit time. Compatibility-Layer hands out images in its own order, so chain images are plain GL textures of
// the game and the chosen one is blitted into an Compatibility-Layer swapchain when the frame is submitted.
#include "vrapi_internal.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace compatibility-layer {

namespace {

constexpr GLenum kFramebufferSrgb = 0x8DB9; // GL_FRAMEBUFFER_SRGB_EXT
constexpr GLenum kRg16 = 0x822C;           // GL_RG16_EXT
constexpr GLenum kSrgbAlpha = 0x8C42;      // GL_SRGB_ALPHA_EXT

// All chains alive, so their Compatibility-Layer copies can be dropped together with the session.
std::vector<ovrTextureSwapChain *> &
chains()
{
	static std::vector<ovrTextureSwapChain *> list;
	return list;
}

GLenum
sized_format(int64_t format)
{
	switch (format) {
	case GL_RGBA: return GL_RGBA8;
	case GL_RGB: return GL_RGB8;
	case kSrgbAlpha: return GL_SRGB8_ALPHA8;
	default: return static_cast<GLenum>(format);
	}
}

GLenum
legacy_format(ovrTextureFormat format)
{
	switch (format) {
	case VRAPI_TEXTURE_FORMAT_565: return GL_RGB565;
	case VRAPI_TEXTURE_FORMAT_5551: return GL_RGB5_A1;
	case VRAPI_TEXTURE_FORMAT_4444: return GL_RGBA4;
	case VRAPI_TEXTURE_FORMAT_8888: return GL_RGBA8;
	case VRAPI_TEXTURE_FORMAT_8888_sRGB: return GL_SRGB8_ALPHA8;
	case VRAPI_TEXTURE_FORMAT_RGBA16F: return GL_RGBA16F;
	case VRAPI_TEXTURE_FORMAT_DEPTH_16: return GL_DEPTH_COMPONENT16;
	case VRAPI_TEXTURE_FORMAT_DEPTH_24: return GL_DEPTH_COMPONENT24;
	case VRAPI_TEXTURE_FORMAT_DEPTH_24_STENCIL_8: return GL_DEPTH24_STENCIL8;
	case VRAPI_TEXTURE_FORMAT_RG16: return kRg16;
	default: return GL_RGBA8;
	}
}

bool
is_unorm8(int64_t format)
{
	return format == GL_RGBA8 || format == GL_RGB8 || format == GL_RGB565 || format == GL_RGB5_A1 ||
	       format == GL_RGBA4 || format == GL_RGB10_A2;
}

GLenum
target_of(const ovrTextureSwapChain &chain)
{
	if (chain.type == VRAPI_TEXTURE_TYPE_CUBE) {
		return GL_TEXTURE_CUBE_MAP;
	}
	return chain.array_size > 1 ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D;
}

GLenum
binding_of(GLenum target)
{
	switch (target) {
	case GL_TEXTURE_CUBE_MAP: return GL_TEXTURE_BINDING_CUBE_MAP;
	case GL_TEXTURE_2D_ARRAY: return GL_TEXTURE_BINDING_2D_ARRAY;
	default: return GL_TEXTURE_BINDING_2D;
	}
}

GLint
gl_filter(ovrTextureFilter filter, bool minification)
{
	switch (filter) {
	case VRAPI_TEXTURE_FILTER_NEAREST: return GL_NEAREST;
	case VRAPI_TEXTURE_FILTER_NEAREST_MIPMAP_LINEAR: return minification ? GL_NEAREST_MIPMAP_LINEAR : GL_NEAREST;
	case VRAPI_TEXTURE_FILTER_LINEAR_MIPMAP_NEAREST: return minification ? GL_LINEAR_MIPMAP_NEAREST : GL_LINEAR;
	case VRAPI_TEXTURE_FILTER_LINEAR_MIPMAP_LINEAR:
	case VRAPI_TEXTURE_FILTER_CUBIC_MIPMAP_LINEAR:
	case VRAPI_TEXTURE_FILTER_CUBIC_MIPMAP_NEAREST: return minification ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR;
	default: return GL_LINEAR;
	}
}

GLint
gl_wrap(ovrTextureWrapMode mode)
{
	return mode == VRAPI_TEXTURE_WRAP_MODE_REPEAT ? GL_REPEAT : GL_CLAMP_TO_EDGE;
}

void
apply_sampler(const ovrTextureSwapChain &chain)
{
	const GLenum target = target_of(chain);
	GLint previous = 0;
	glGetIntegerv(binding_of(target), &previous);
	for (unsigned int texture : chain.textures) {
		glBindTexture(target, texture);
		glTexParameteri(target, GL_TEXTURE_MIN_FILTER,
		                chain.levels > 1 ? gl_filter(chain.sampler.MinFilter, true) : GL_LINEAR);
		glTexParameteri(target, GL_TEXTURE_MAG_FILTER, gl_filter(chain.sampler.MagFilter, false));
		glTexParameteri(target, GL_TEXTURE_WRAP_S, gl_wrap(chain.sampler.WrapModeS));
		glTexParameteri(target, GL_TEXTURE_WRAP_T, gl_wrap(chain.sampler.WrapModeT));
	}
	glBindTexture(target, static_cast<GLuint>(previous));
}

ovrTextureSwapChain *
create_chain(ovrTextureType type, int64_t format, int width, int height, int levels, int array_size,
             int buffer_count)
{
	if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
		VRAPI_WARN("Swap chain requested without a current GL context");
		return nullptr;
	}
	if (width <= 0 || height <= 0 || type == VRAPI_TEXTURE_TYPE_MAX) {
		return nullptr;
	}
	auto *chain = new ovrTextureSwapChain();
	chain->type = type;
	chain->format = sized_format(format);
	chain->width = width;
	chain->height = height;
	const int max_levels = static_cast<int>(std::floor(std::log2(std::max(width, height)))) + 1;
	chain->levels = std::clamp(levels, 1, max_levels);
	chain->array_size = type == VRAPI_TEXTURE_TYPE_2D_ARRAY ? std::max(array_size, 2) : 1;
	chain->sampler = vrapi_DefaultTextureSamplerState(type, chain->levels);

	const int count = std::clamp(buffer_count, 1, 16);
	chain->textures.assign(count, 0);
	chain->owned.assign(count, true);
	glGenTextures(count, chain->textures.data());

	const GLenum target = target_of(*chain);
	GLint previous = 0;
	glGetIntegerv(binding_of(target), &previous);
	for (unsigned int texture : chain->textures) {
		glBindTexture(target, texture);
		if (target == GL_TEXTURE_2D_ARRAY) {
			glTexStorage3D(target, chain->levels, static_cast<GLenum>(chain->format), width, height,
			               chain->array_size);
		} else {
			glTexStorage2D(target, chain->levels, static_cast<GLenum>(chain->format), width, height);
		}
	}
	glBindTexture(target, static_cast<GLuint>(previous));
	apply_sampler(*chain);
	if (glGetError() != GL_NO_ERROR) {
		VRAPI_WARN("Swap chain %dx%d format 0x%llx may be incomplete", width, height,
		           static_cast<long long>(chain->format));
	}

	std::lock_guard<std::recursive_mutex> guard(global().lock);
	chains().push_back(chain);
	return chain;
}

void
drop_copies(ovrTextureSwapChain *chain)
{
	for (ChainCopy &copy : chain->copies) {
		if (copy.xr != nullptr) {
			global().backend.destroy_swapchain(copy.xr);
			copy.xr = nullptr;
		}
	}
}

bool
has_extension(const char *name)
{
	const auto *extensions = reinterpret_cast<const char *>(glGetString(GL_EXTENSIONS));
	return extensions != nullptr && std::strstr(extensions, name) != nullptr;
}

// Framebuffer objects are not shared between contexts, so there is a pair per context.
std::array<GLuint, 2>
framebuffers()
{
	static std::vector<std::pair<EGLContext, std::array<GLuint, 2>>> cache;
	const EGLContext context = eglGetCurrentContext();
	for (const auto &entry : cache) {
		if (entry.first == context) {
			return entry.second;
		}
	}
	std::array<GLuint, 2> pair{};
	glGenFramebuffers(2, pair.data());
	cache.emplace_back(context, pair);
	return pair;
}

bool
supported(int64_t format)
{
	const std::vector<int64_t> &formats = global().backend.swapchain_formats();
	return std::find(formats.begin(), formats.end(), format) != formats.end();
}

// Picks the Compatibility-Layer format for a chain. Gear VR showed 8-bit eye buffers as they are, while Compatibility-Layer
// treats GL_RGBA8 as linear; such images go to an sRGB swapchain with conversion switched off.
int64_t
choose_format(int64_t app_format, bool want_raw, bool &raw)
{
	static const bool write_control = has_extension("GL_EXT_sRGB_write_control");
	raw = false;
	if (app_format == GL_SRGB8_ALPHA8 && supported(GL_SRGB8_ALPHA8)) {
		return GL_SRGB8_ALPHA8;
	}
	if (is_unorm8(app_format)) {
		if (want_raw && write_control && supported(GL_SRGB8_ALPHA8)) {
			raw = true;
			return GL_SRGB8_ALPHA8;
		}
		if (want_raw && !write_control) {
			static bool warned = false;
			if (!warned) {
				VRAPI_WARN("No GL_EXT_sRGB_write_control: colors of 8-bit eye buffers may look washed out");
				warned = true;
			}
		}
		if (supported(GL_RGBA8)) {
			return GL_RGBA8;
		}
	}
	if (supported(app_format)) {
		return app_format;
	}
	for (int64_t fallback : {static_cast<int64_t>(GL_SRGB8_ALPHA8), static_cast<int64_t>(GL_RGBA8)}) {
		if (supported(fallback)) {
			return fallback;
		}
	}
	const std::vector<int64_t> &formats = global().backend.swapchain_formats();
	return formats.empty() ? GL_SRGB8_ALPHA8 : formats.front();
}

void
blit(unsigned int source, GLenum source_target, int layer, const std::array<int, 4> &area, GLuint destination,
     bool raw)
{
	const std::array<GLuint, 2> fbo = framebuffers();
	GLint read_binding = 0;
	GLint draw_binding = 0;
	glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read_binding);
	glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw_binding);
	const GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
	const GLboolean discard = glIsEnabled(GL_RASTERIZER_DISCARD);
	GLboolean srgb_write = GL_TRUE;
	if (raw) {
		srgb_write = glIsEnabled(kFramebufferSrgb);
		glDisable(kFramebufferSrgb);
	}
	glDisable(GL_SCISSOR_TEST);
	glDisable(GL_RASTERIZER_DISCARD);

	glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo[0]);
	if (source_target == GL_TEXTURE_2D_ARRAY) {
		glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, source, 0, layer);
	} else {
		glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source, 0);
	}
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo[1]);
	glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, destination, 0);

	const int width = area[2] - area[0];
	const int height = area[3] - area[1];
	glBlitFramebuffer(area[0], area[1], area[2], area[3], 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

	// Detach so deleted game textures are not kept alive by these framebuffers.
	glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
	glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
	glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(read_binding));
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(draw_binding));
	if (scissor) {
		glEnable(GL_SCISSOR_TEST);
	}
	if (discard) {
		glEnable(GL_RASTERIZER_DISCARD);
	}
	if (raw && srgb_write) {
		glEnable(kFramebufferSrgb);
	}
}

} // namespace

bool
is_real_chain(const ovrTextureSwapChain *chain)
{
	// VRAPI_DEFAULT_TEXTURE_SWAPCHAIN and _LOADING_ICON are passed as small integer pointers.
	return reinterpret_cast<uintptr_t>(chain) > VRAPI_DEFAULT_TEXTURE_SWAPCHAIN_LOADING_ICON;
}

void
release_swapchain_copies()
{
	std::lock_guard<std::recursive_mutex> guard(global().lock);
	for (ovrTextureSwapChain *chain : chains()) {
		drop_copies(chain);
	}
}

bool
copy_eye_image(ovrTextureSwapChain *chain, int index, int eye, const ovrRectf &rect, bool raw, EyeImage &out)
{
	XrBackend &backend = global().backend;
	if (!is_real_chain(chain) || chain->textures.empty() || chain->type == VRAPI_TEXTURE_TYPE_CUBE ||
	    !backend.session_running()) {
		return false;
	}
	const int count = static_cast<int>(chain->textures.size());
	const unsigned int texture = chain->textures[((index % count) + count) % count];

	const int x0 = std::clamp(static_cast<int>(std::lround(rect.x * chain->width)), 0, chain->width - 1);
	const int y0 = std::clamp(static_cast<int>(std::lround(rect.y * chain->height)), 0, chain->height - 1);
	const int x1 =
	    std::clamp(static_cast<int>(std::lround((rect.x + rect.width) * chain->width)), x0 + 1, chain->width);
	const int y1 =
	    std::clamp(static_cast<int>(std::lround((rect.y + rect.height) * chain->height)), y0 + 1, chain->height);
	const int width = x1 - x0;
	const int height = y1 - y0;

	bool raw_copy = false;
	const int64_t format = choose_format(chain->format, raw, raw_copy);
	ChainCopy &copy = chain->copies[eye];
	// Only this eye's copy: the other eye may already sit in the frame being built.
	if (copy.xr != nullptr &&
	    (copy.xr->width != width || copy.xr->height != height || copy.xr->format != format)) {
		backend.destroy_swapchain(copy.xr);
		copy.xr = nullptr;
	}
	if (copy.xr == nullptr) {
		copy.xr = backend.create_swapchain(width, height, format, 1);
		if (copy.xr == nullptr) {
			return false;
		}
	}
	copy.raw = raw_copy;

	static unsigned probes = 0;
	if (probes++ % 203 == 0) {
		// What the game drew: the middle pixel of this eye's image.
		GLint previous = 0;
		glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previous);
		const std::array<GLuint, 2> fbo = framebuffers();
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo[0]);
		if (target_of(*chain) == GL_TEXTURE_2D_ARRAY) {
			glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texture, 0, chain->array_size > 1 ? eye : 0);
		} else {
			glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
		}
		unsigned char pixel[4] = {};
		int brightest = 0;
		for (int gy = 1; gy < 8; gy++) {
			for (int gx = 1; gx < 8; gx++) {
				unsigned char sample[4] = {};
				glReadPixels(x0 + (x1 - x0) * gx / 8, y0 + (y1 - y0) * gy / 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
				const int sum = sample[0] + sample[1] + sample[2];
				if (sum >= brightest) {
					brightest = sum;
					std::copy(sample, sample + 4, pixel);
				}
			}
		}
		const GLenum status = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
		glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(previous));
		VRAPI_LOG("eye %d: chain %dx%d format 0x%llx array %d, rect %.3f %.3f %.3f %.3f, index %d, brightest %u %u %u %u, fbo 0x%x, error 0x%x",
		          eye, chain->width, chain->height, static_cast<long long>(chain->format), chain->array_size, rect.x, rect.y,
		          rect.width, rect.height, index, pixel[0], pixel[1], pixel[2], pixel[3], status, glGetError());
	}
	const uint32_t image = backend.acquire_image(copy.xr);
	blit(texture, target_of(*chain), chain->array_size > 1 ? eye : 0, {x0, y0, x1, y1},
	     copy.xr->images[image].image, raw_copy);
	backend.release_image(copy.xr);

	out.swapchain = copy.xr;
	out.rect = {{0, 0}, {width, height}};
	return true;
}

} // namespace compatibility-layer

using namespace compatibility-layer;

extern "C" {

ovrTextureSwapChain *
vrapi_CreateTextureSwapChain4(const ovrSwapChainCreateInfo *createInfo)
{
	VRAPI_TRACE("vrapi_CreateTextureSwapChain4");
	if (createInfo == nullptr) {
		return nullptr;
	}
	ovrTextureType type = VRAPI_TEXTURE_TYPE_2D;
	if (createInfo->FaceCount == 6) {
		type = VRAPI_TEXTURE_TYPE_CUBE;
	} else if (createInfo->ArraySize > 1) {
		type = VRAPI_TEXTURE_TYPE_2D_ARRAY;
	}
	return create_chain(type, createInfo->Format, createInfo->Width, createInfo->Height, createInfo->Levels,
	                    createInfo->ArraySize, createInfo->BufferCount);
}

ovrTextureSwapChain *
vrapi_CreateTextureSwapChain3(ovrTextureType type, int64_t format, int width, int height, int levels, int bufferCount)
{
	VRAPI_TRACE("vrapi_CreateTextureSwapChain3");
	return create_chain(type, format, width, height, levels, 2, bufferCount);
}

ovrTextureSwapChain *
vrapi_CreateTextureSwapChain2(ovrTextureType type, ovrTextureFormat format, int width, int height, int levels,
                              int bufferCount)
{
	VRAPI_TRACE("vrapi_CreateTextureSwapChain2");
	return create_chain(type, legacy_format(format), width, height, levels, 2, bufferCount);
}

ovrTextureSwapChain *
vrapi_CreateTextureSwapChain(ovrTextureType type, ovrTextureFormat format, int width, int height, int levels,
                             bool buffered)
{
	VRAPI_TRACE("vrapi_CreateTextureSwapChain");
	return create_chain(type, legacy_format(format), width, height, levels, 2, buffered ? 3 : 1);
}

// Video surfaces need a compositor that samples SurfaceTexture directly; Compatibility-Layer has no such layer.
ovrTextureSwapChain *
vrapi_CreateAndroidSurfaceSwapChain(int, int)
{
	VRAPI_TRACE("vrapi_CreateAndroidSurfaceSwapChain");
	VRAPI_WARN("Android surface swap chains are not supported");
	return nullptr;
}

ovrTextureSwapChain *
vrapi_CreateAndroidSurfaceSwapChain2(int width, int height, bool)
{
	VRAPI_TRACE("vrapi_CreateAndroidSurfaceSwapChain2");
	return vrapi_CreateAndroidSurfaceSwapChain(width, height);
}

ovrTextureSwapChain *
vrapi_CreateAndroidSurfaceSwapChain3(int width, int height, uint64_t)
{
	VRAPI_TRACE("vrapi_CreateAndroidSurfaceSwapChain3");
	return vrapi_CreateAndroidSurfaceSwapChain(width, height);
}

void
vrapi_DestroyTextureSwapChain(ovrTextureSwapChain *chain)
{
	VRAPI_TRACE("vrapi_DestroyTextureSwapChain");
	if (!is_real_chain(chain)) {
		return;
	}
	std::lock_guard<std::recursive_mutex> guard(global().lock);
	std::vector<ovrTextureSwapChain *> &list = chains();
	list.erase(std::remove(list.begin(), list.end(), chain), list.end());
	drop_copies(chain);
	if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
		for (size_t i = 0; i < chain->textures.size(); i++) {
			if (chain->owned[i]) {
				glDeleteTextures(1, &chain->textures[i]);
			}
		}
	}
	delete chain;
}

int
vrapi_GetTextureSwapChainLength(ovrTextureSwapChain *chain)
{
	VRAPI_TRACE("vrapi_GetTextureSwapChainLength");
	return is_real_chain(chain) ? static_cast<int>(chain->textures.size()) : 1;
}

unsigned int
vrapi_GetTextureSwapChainHandle(ovrTextureSwapChain *chain, int index)
{
	VRAPI_TRACE("vrapi_GetTextureSwapChainHandle");
	if (!is_real_chain(chain) || index < 0 || index >= static_cast<int>(chain->textures.size())) {
		return 0;
	}
	return chain->textures[index];
}

jobject
vrapi_GetTextureSwapChainAndroidSurface(ovrTextureSwapChain *)
{
	VRAPI_TRACE("vrapi_GetTextureSwapChainAndroidSurface");
	return nullptr;
}

ovrResult
vrapi_SetTextureSwapChainSamplerState(ovrTextureSwapChain *chain, const ovrTextureSamplerState *samplerState)
{
	VRAPI_TRACE("vrapi_SetTextureSwapChainSamplerState");
	if (!is_real_chain(chain) || samplerState == nullptr) {
		return ovrError_InvalidParameter;
	}
	chain->sampler = *samplerState;
	if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
		apply_sampler(*chain);
	}
	return ovrSuccess;
}

ovrResult
vrapi_GetTextureSwapChainSamplerState(ovrTextureSwapChain *chain, ovrTextureSamplerState *samplerState)
{
	VRAPI_TRACE("vrapi_GetTextureSwapChainSamplerState");
	if (!is_real_chain(chain) || samplerState == nullptr) {
		return ovrError_InvalidParameter;
	}
	*samplerState = chain->sampler;
	return ovrSuccess;
}

} // extern "C"

// Older SDKs let the game put its own GL texture into a chain slot.
VRAPI_EXTRA_EXPORT void
vrapi_SetTextureSwapChainHandle(ovrTextureSwapChain *chain, int index, unsigned int handle)
{
	VRAPI_TRACE("vrapi_SetTextureSwapChainHandle");
	if (!is_real_chain(chain) || index < 0 || index >= static_cast<int>(chain->textures.size())) {
		return;
	}
	if (chain->owned[index] && eglGetCurrentContext() != EGL_NO_CONTEXT) {
		glDeleteTextures(1, &chain->textures[index]);
	}
	chain->textures[index] = handle;
	chain->owned[index] = false;
}

VRAPI_EXTRA_EXPORT ovrTextureSwapChain *
vrapi_CreateTextureSwapChainCrossProcess()
{
	VRAPI_TRACE("vrapi_CreateTextureSwapChainCrossProcess");
	return nullptr;
}
