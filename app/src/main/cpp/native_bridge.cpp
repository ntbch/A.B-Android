#include <jni.h>
#include <MNN/Interpreter.hpp>
#include <llm/llm.hpp>

#include <mutex>
#include <sstream>
#include <string>

namespace {

using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

std::mutex model_mutex;
Llm* model = nullptr;

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

void unload_model() {
    if (model != nullptr) {
        Llm::destroy(model);
        model = nullptr;
    }
}

bool load_with_backend(const std::string& config_path, const std::string& cache_path, const char* backend) {
    model = Llm::createLLM(config_path);
    if (model == nullptr) {
        return false;
    }

    const std::string config =
            std::string("{\"backend_type\":\"") + backend +
            "\",\"thread_num\":4,\"max_new_tokens\":64,\"use_mmap\":true,\"tmp_path\":\"" +
            cache_path +
            "\",\"jinja\":{\"context\":{\"enable_thinking\":false}}}";
    return model->set_config(config) && model->load();
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

    if (load_with_backend(config, cache, "opencl")) {
        return to_jstring(env, "OPENCL");
    }

    unload_model();
    if (load_with_backend(config, cache, "cpu")) {
        return to_jstring(env, "CPU");
    }

    unload_model();
    return to_jstring(env, "ERROR: MNN could not load this model with OpenCL or CPU.");
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
JNIEXPORT void JNICALL
Java_com_ab_assistant_NativeBridge_unloadModel(
        JNIEnv* /* env */,
        jobject /* thiz */) {

    std::lock_guard<std::mutex> lock(model_mutex);
    unload_model();
}
