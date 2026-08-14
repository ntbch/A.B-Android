#include <jni.h>
#include <MNN/Interpreter.hpp>
#include <llm/llm.hpp>

#include <algorithm>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {

using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

std::mutex model_mutex;
Llm* model = nullptr;
std::vector<int> cached_prompt_tokens;
constexpr size_t kMinimumCachedPrefixTokens = 32;

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* utf8 = env->GetStringUTFChars(value, nullptr);
    if (utf8 == nullptr) {
        return {};
    }

    std::string result(utf8);
    env->ReleaseStringUTFChars(value, utf8);
    return result;
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string base64_encode(const std::string& value) {
    static constexpr char table[] =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string encoded;
    encoded.reserve(((value.size() + 2) / 3) * 4);
    unsigned int accumulator = 0;
    int bits = -6;
    for (unsigned char byte : value) {
        accumulator = (accumulator << 8) | byte;
        bits += 8;
        while (bits >= 0) {
            encoded.push_back(table[(accumulator >> bits) & 0x3F]);
            bits -= 6;
        }
    }
    if (bits > -6) {
        encoded.push_back(table[((accumulator << 8) >> (bits + 8)) & 0x3F]);
    }
    while (encoded.size() % 4 != 0) {
        encoded.push_back('=');
    }
    return encoded;
}

long long micros_to_millis(int64_t micros) {
    return micros > 0 ? micros / 1000 : -1;
}

std::string generation_payload(
        const std::string& response,
        const MNN::Transformer::LlmContext* context,
        bool prompt_cache_hit = false,
        size_t cached_prompt_tokens_count = 0,
        int prompt_tokens_override = -1) {
    const auto prompt_tokens = prompt_tokens_override >= 0
            ? prompt_tokens_override
            : (context != nullptr ? context->prompt_len : -1);
    const auto prefill_ms = context != nullptr ? micros_to_millis(context->prefill_us) : -1;
    const auto ttft_ms = context != nullptr ? micros_to_millis(context->ttfa_us) : -1;
    const auto generated_tokens = context != nullptr
            ? static_cast<long long>(context->output_tokens.size())
            : -1;
    const auto decode_ms = context != nullptr ? micros_to_millis(context->decode_us) : -1;
    const auto decode_tps_milli = generated_tokens > 0 && decode_ms > 0
            ? (generated_tokens * 1000000LL) / decode_ms
            : -1;
    const auto status = context != nullptr ? static_cast<int>(context->status) : -1;

    std::ostringstream payload;
    payload << "AB_GENERATION_V1\n"
            << "status=" << status << '\n'
            << "prompt_tokens=" << prompt_tokens << '\n'
            << "prefill_ms=" << prefill_ms << '\n'
            << "ttft_ms=" << ttft_ms << '\n'
            << "generated_tokens=" << generated_tokens << '\n'
            << "decode_ms=" << decode_ms << '\n'
            << "decode_tps_milli=" << decode_tps_milli << '\n'
            << "prompt_cache_hit=" << (prompt_cache_hit ? 1 : 0) << '\n'
            << "cached_prompt_tokens=" << cached_prompt_tokens_count << '\n'
            << "response_b64=" << base64_encode(response) << '\n';
    return payload.str();
}

void unload_model() {
    if (model != nullptr) {
        Llm::destroy(model);
        model = nullptr;
    }
    cached_prompt_tokens.clear();
}

bool load_with_backend(const std::string& config_path, const std::string& cache_path, const char* backend) {
    model = Llm::createLLM(config_path);
    if (model == nullptr) {
        return false;
    }

    const std::string config =
            std::string("{\"backend_type\":\"") + backend +
            "\",\"thread_num\":4,\"max_all_tokens\":2048,\"max_new_tokens\":64,\"sampler_type\":\"greedy\",\"use_mmap\":true,\"reuse_kv\":true,\"tmp_path\":\"" +
            cache_path +
            "\",\"jinja\":{\"context\":{\"enable_thinking\":false}}}";
    return model->set_config(config) && model->load();
}

size_t common_prefix_length(const std::vector<int>& left, const std::vector<int>& right) {
    const auto limit = std::min(left.size(), right.size());
    size_t common = 0;
    while (common < limit && left[common] == right[common]) {
        ++common;
    }
    return common;
}

std::vector<int> prepare_generation_input(
        const std::string& request,
        bool* cache_hit,
        size_t* cached_tokens_count) {
    *cache_hit = false;
    *cached_tokens_count = 0;
    // Keep the structured path identical to MNN's response(string) path:
    // render the model chat template first so enable_thinking=false and any
    // model-specific assistant markers are actually present in the prompt.
    const auto rendered_request = model->apply_chat_template(request);
    const auto full_tokens = model->tokenizer_encode(rendered_request.empty() ? request : rendered_request);
    if (full_tokens.empty()) {
        model->reset();
        cached_prompt_tokens.clear();
        return full_tokens;
    }

    if (!cached_prompt_tokens.empty() && model->getCurrentHistory() > 0) {
        const auto common = common_prefix_length(cached_prompt_tokens, full_tokens);
        if (common >= kMinimumCachedPrefixTokens && common < full_tokens.size()) {
            model->eraseHistory(common, model->getCurrentHistory());
            *cache_hit = true;
            *cached_tokens_count = common;
            cached_prompt_tokens = full_tokens;
            return std::vector<int>(full_tokens.begin() + common, full_tokens.end());
        }
    }

    // No safe reusable prefix: clear all prior KV state before a full prefill.
    model->reset();
    cached_prompt_tokens = full_tokens;
    return full_tokens;
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ab_assistant_NativeBridge_hello(
        JNIEnv* env,
        jobject /* thiz */) {

    return env->NewStringUTF("JNI OK");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ab_assistant_NativeBridge_mnnVersion(
        JNIEnv* env,
        jobject /* thiz */) {

    return env->NewStringUTF(MNN::getVersion());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ab_assistant_NativeBridge_loadModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring config_path,
        jstring cache_path) {

    const std::string config = to_string(env, config_path);
    const std::string cache = to_string(env, cache_path);
    if (config.empty() || cache.empty()) {
        return to_jstring(env, "ERROR: Model config and cache paths are required.");
    }

    std::lock_guard<std::mutex> lock(model_mutex);
    unload_model();

    if (load_with_backend(config, cache, "cpu")) {
        return to_jstring(env, "CPU");
    }

    unload_model();
    if (load_with_backend(config, cache, "opencl")) {
        return to_jstring(env, "OPENCL");
    }

    unload_model();
    if (load_with_backend(config, cache, "vulkan")) {
        return to_jstring(env, "VULKAN");
    }

    unload_model();
    return to_jstring(env, "ERROR: MNN could not load this model with CPU, OpenCL, or Vulkan.");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ab_assistant_NativeBridge_loadModelWithBackend(
        JNIEnv* env,
        jobject /* thiz */,
        jstring config_path,
        jstring cache_path,
        jstring backend_name) {

    const std::string config = to_string(env, config_path);
    const std::string cache = to_string(env, cache_path);
    const std::string backend = to_string(env, backend_name);
    if (config.empty() || cache.empty() ||
            (backend != "opencl" && backend != "vulkan" && backend != "cpu")) {
        return to_jstring(env, "ERROR: Unsupported MNN backend request.");
    }

    std::lock_guard<std::mutex> lock(model_mutex);
    unload_model();
    if (!load_with_backend(config, cache, backend.c_str())) {
        unload_model();
        return to_jstring(env, "ERROR: Requested MNN backend could not load the model.");
    }
    if (backend == "opencl") return to_jstring(env, "OPENCL");
    if (backend == "vulkan") return to_jstring(env, "VULKAN");
    return to_jstring(env, "CPU");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ab_assistant_NativeBridge_generate(
        JNIEnv* env,
        jobject /* thiz */,
        jstring prompt,
        jint max_new_tokens) {

    const std::string request = to_string(env, prompt);
    if (request.empty()) {
        return to_jstring(env, "ERROR: Prompt is empty.");
    }
    if (max_new_tokens < 1 || max_new_tokens > 64) {
        return to_jstring(env, "ERROR: max_new_tokens must be between 1 and 64.");
    }

    std::lock_guard<std::mutex> lock(model_mutex);
    if (model == nullptr) {
        return to_jstring(env, "ERROR: Model is not loaded.");
    }

    model->reset();
    std::ostringstream response;
    model->response(request, &response, "", max_new_tokens);

    const auto* context = model->getContext();
    if (context == nullptr || context->status == LlmStatus::INTERNAL_ERROR || context->status == LlmStatus::TIMEOUT) {
        return to_jstring(env, "ERROR: MNN generation failed.");
    }

    return to_jstring(env, response.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_ab_assistant_NativeBridge_generateWithMetrics(
        JNIEnv* env,
        jobject /* thiz */,
        jstring prompt,
        jint max_new_tokens) {

    const std::string request = to_string(env, prompt);
    if (request.empty()) {
        return to_jstring(env, generation_payload("ERROR: Prompt is empty.", nullptr));
    }
    if (max_new_tokens < 1 || max_new_tokens > 64) {
        return to_jstring(env, generation_payload("ERROR: max_new_tokens must be between 1 and 64.", nullptr));
    }

    std::lock_guard<std::mutex> lock(model_mutex);
    if (model == nullptr) {
        return to_jstring(env, generation_payload("ERROR: Model is not loaded.", nullptr));
    }

    // Some MNN architectures (including Qwen3.5 Omni) need model-specific
    // prompt construction before tokenization. Passing pre-tokenized text into
    // response() bypasses that path and can abort in the native tokenizer.
    model->reset();
    cached_prompt_tokens.clear();
    std::ostringstream response;
    model->response(request, &response, "", max_new_tokens);
    const auto* context = model->getContext();
    if (context == nullptr || context->status == LlmStatus::INTERNAL_ERROR ||
            context->status == LlmStatus::TIMEOUT) {
        cached_prompt_tokens.clear();
        return to_jstring(env, generation_payload("ERROR: MNN generation failed.", context));
    }
    return to_jstring(
            env,
            generation_payload(
                    response.str(),
                    context,
                    false,
                    0));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_ab_assistant_NativeBridge_unloadModel(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    std::lock_guard<std::mutex> lock(model_mutex);
    unload_model();
}
