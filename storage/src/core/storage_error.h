#ifndef STORAGE_CORE_STORAGE_ERROR_H
#define STORAGE_CORE_STORAGE_ERROR_H

#include <stdexcept>
#include <string>

// 存储核心统一错误异常：code 为 readme 第 3 节定义的错误码
// （INVALID_PLAN / TABLE_NOT_FOUND / TABLE_ALREADY_EXISTS / COLUMN_NOT_FOUND /
//   TYPE_MISMATCH / INTERNAL_ERROR），由各操作捕获后转为 error JSON 输出
class StorageError : public std::runtime_error
{
public:
    StorageError(std::string code, const std::string &message);

    const std::string &code() const;

private:
    std::string code_;
};

#endif
