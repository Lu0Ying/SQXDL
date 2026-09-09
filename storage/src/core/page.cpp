#include "page.h"

#include <cstring>

Page::Page() : id_(INVALID_PAGE_ID), pin_count_(0), dirty_(false)
{
}

page_id_t Page::id() const
{
    return id_;
}

bool Page::is_dirty() const
{
    return dirty_;
}

int Page::pin_count() const
{
    return pin_count_;
}

char *Page::data()
{
    return data_;
}

const char *Page::data() const
{
    return data_;
}

void Page::reset(page_id_t page_id)
{
    id_ = page_id;
    pin_count_ = 0;
    dirty_ = false;
    std::memset(data_, 0, PAGE_SIZE);
}
